package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.malyah.accountmanager.identity.application.MembershipLifecycleEvent.Type;
import com.malyah.accountmanager.identity.application.port.MembershipRepository;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.domain.SpaceRole;

class MembershipManagementServiceTest {
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    private final MembershipRepository repository = mock(MembershipRepository.class);
    private final SessionRevoker sessions = mock(SessionRevoker.class);
    private MembershipManagementService service;

    @BeforeEach
    void setUp() {
        service = new MembershipManagementService(
                repository, sessions, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findActiveActor("admin@example.com")).thenReturn(Optional.of(admin()));
        when(repository.findActiveActor("guest@example.com")).thenReturn(Optional.of(guest()));
        when(repository.findActiveMember(SPACE, ADMIN)).thenReturn(Optional.of(admin()));
        when(repository.findActiveMember(SPACE, GUEST)).thenReturn(Optional.of(guest()));
    }

    @Test
    void listsOnlyMembersFromActorsActiveSpaceAndMarksCurrentUser() {
        var members = List.of(new ManagedMember(ADMIN, "Admin", "admin@example.com", SpaceRole.ADMINISTRATOR, true));
        when(repository.findActiveMembers(SPACE, ADMIN)).thenReturn(members);

        assertThat(service.members(" ADMIN@example.com ")).isSameAs(members);
        verify(repository).findActiveMembers(SPACE, ADMIN);
    }

    @Test
    void administratorRemovesGuestRevokesSessionsAndWritesDurableEvent() {
        service.remove("admin@example.com", GUEST);

        verify(repository).lockSpace(SPACE);
        verify(repository).endMembership(SPACE, GUEST, ADMIN, "ADMIN_REMOVAL", NOW);
        verify(sessions).revokeAll("guest@example.com");
        var event = ArgumentCaptor.forClass(MembershipLifecycleEvent.class);
        verify(repository).append(event.capture());
        assertThat(event.getValue()).usingRecursiveComparison().isEqualTo(new MembershipLifecycleEvent(
                EVENT, SPACE, ADMIN, GUEST, Type.MEMBER_REMOVED,
                SpaceRole.ADMINISTRATOR, SpaceRole.ADMINISTRATOR, SpaceRole.GUEST, null, NOW));
    }

    @Test
    void guestCannotCallAdministrativeOperationsDirectly() {
        assertThatThrownBy(() -> service.remove("guest@example.com", ADMIN))
                .isInstanceOf(MembershipAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.transferAdministration("guest@example.com", ADMIN))
                .isInstanceOf(MembershipAdministratorRequiredException.class);
        verify(repository, never()).endMembership(any(), any(), any(), any(), any());
    }

    @Test
    void administratorCannotRemoveSelfOrLeaveWithoutSuccessorFlow() {
        assertThatThrownBy(() -> service.remove("admin@example.com", ADMIN))
                .isInstanceOf(MembershipConflictException.class)
                .hasMessageContaining("não pode remover");
        assertThatThrownBy(() -> service.leave("admin@example.com"))
                .isInstanceOf(MembershipConflictException.class)
                .hasMessageContaining("Transfira");
        verify(sessions, never()).revokeAll(any());
    }

    @Test
    void guestLeavesAndKeepsAnAuditableIdentityReference() {
        service.leave("guest@example.com");

        verify(repository).endMembership(SPACE, GUEST, GUEST, "VOLUNTARY_EXIT", NOW);
        verify(sessions).revokeAll("guest@example.com");
        var event = ArgumentCaptor.forClass(MembershipLifecycleEvent.class);
        verify(repository).append(event.capture());
        assertThat(event.getValue().type()).isEqualTo(Type.MEMBER_LEFT);
        assertThat(event.getValue().subjectUserId()).isEqualTo(GUEST);
        assertThat(event.getValue().subjectNewRole()).isNull();
    }

    @Test
    void transferSwapsBothRolesAndRecordsConsentBoundaryEvent() {
        service.transferAdministration("admin@example.com", GUEST);

        verify(repository).transferAdministration(SPACE, ADMIN, GUEST);
        var event = ArgumentCaptor.forClass(MembershipLifecycleEvent.class);
        verify(repository).append(event.capture());
        assertThat(event.getValue()).usingRecursiveComparison().isEqualTo(new MembershipLifecycleEvent(
                EVENT, SPACE, ADMIN, GUEST, Type.ADMINISTRATION_TRANSFERRED,
                SpaceRole.ADMINISTRATOR, SpaceRole.GUEST, SpaceRole.GUEST, SpaceRole.ADMINISTRATOR, NOW));
        verify(sessions, never()).revokeAll(any());
    }

    @Test
    void transferRejectsSelfInactiveOrAlreadyAdministrativeTarget() {
        assertThatThrownBy(() -> service.transferAdministration("admin@example.com", ADMIN))
                .isInstanceOf(MembershipConflictException.class);
        when(repository.findActiveMember(SPACE, GUEST)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transferAdministration("admin@example.com", GUEST))
                .isInstanceOf(ManagedMemberNotFoundException.class);
    }

    @Test
    void missingOrEndedAssociationCannotReadOrMutateSpace() {
        when(repository.findActiveActor("former@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.members("former@example.com"))
                .isInstanceOf(ManagedMemberNotFoundException.class);
        assertThatThrownBy(() -> service.remove("admin@example.com", null))
                .isInstanceOf(ManagedMemberNotFoundException.class);
    }

    @Test
    void concurrentRepeatedExitIsReportedAsConflictAfterSpaceLock() {
        when(repository.findActiveActor("guest@example.com"))
                .thenReturn(Optional.of(guest()), Optional.empty());

        assertThatThrownBy(() -> service.leave("guest@example.com"))
                .isInstanceOf(MembershipConflictException.class)
                .hasMessageContaining("encerrada durante");
        verify(repository).lockSpace(SPACE);
        verify(sessions, never()).revokeAll(any());
    }

    private MembershipActor admin() {
        return new MembershipActor(ADMIN, SPACE, "admin@example.com", SpaceRole.ADMINISTRATOR);
    }

    private MembershipActor guest() {
        return new MembershipActor(GUEST, SPACE, "guest@example.com", SpaceRole.GUEST);
    }
}
