package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.port.AccessTokenCodec;
import com.malyah.accountmanager.identity.application.port.InvitationRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;
import com.malyah.accountmanager.identity.domain.SpaceRole;

class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID INVITATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID GENERATED_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private final InvitationRepository repository = mock(InvitationRepository.class);
    private final AccessTokenCodec codec = mock(AccessTokenCodec.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private InvitationService service;

    @BeforeEach
    void setUp() {
        service = new InvitationService(repository, codec, () -> GENERATED_ID, hasher,
                new PasswordPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
        given(repository.findActorByEmail("admin@example.com"))
                .willReturn(Optional.of(new InvitationActor(ADMIN_ID, SPACE_ID, SpaceRole.ADMINISTRATOR)));
        given(repository.countActiveMembers(SPACE_ID)).willReturn(1);
        given(codec.generate()).willReturn("raw-token");
        given(codec.hash("raw-token")).willReturn("hashed-token");
    }

    @Test
    void createsSevenDayHashedInvitationOnlyForAdministrator() {
        given(repository.findActiveBySpaceForUpdate(SPACE_ID))
                .willReturn(Optional.empty(), Optional.of(invitation("guest@example.com", NOW.plusSeconds(604800))));

        var pending = service.prepareNew("ADMIN@example.com", " Guest@Example.com ");

        assertThat(pending.rawToken()).isEqualTo("raw-token");
        assertThat(pending.recipient()).isEqualTo("guest@example.com");
        then(repository).should().store(new InvitationRegistration(
                GENERATED_ID, SPACE_ID, "guest@example.com", ADMIN_ID, "hashed-token",
                NOW.plusSeconds(604800), NOW));

        given(repository.findActorByEmail("guest@example.com"))
                .willReturn(Optional.of(new InvitationActor(GENERATED_ID, SPACE_ID, SpaceRole.GUEST)));
        assertThatThrownBy(() -> service.prepareNew("guest@example.com", "other@example.com"))
                .isInstanceOf(InvitationAdministratorRequiredException.class);
    }

    @Test
    void refusesPendingInvitationFullSpaceAndUnavailableTarget() {
        given(repository.findActiveBySpaceForUpdate(SPACE_ID))
                .willReturn(Optional.of(invitation("guest@example.com", NOW.plusSeconds(1))));
        assertThatThrownBy(() -> service.prepareNew("admin@example.com", "other@example.com"))
                .isInstanceOf(InvitationAlreadyPendingException.class);

        given(repository.countActiveMembers(SPACE_ID)).willReturn(2);
        assertThatThrownBy(() -> service.prepareNew("admin@example.com", "other@example.com"))
                .isInstanceOf(SpaceMemberLimitReachedException.class);

        given(repository.countActiveMembers(SPACE_ID)).willReturn(1);
        given(repository.findAccountByEmail("other@example.com"))
                .willReturn(Optional.of(new InvitationAccount(GENERATED_ID, "other@example.com", true, UUID.randomUUID())));
        assertThatThrownBy(() -> service.prepareNew("admin@example.com", "other@example.com"))
                .isInstanceOf(InvitationTargetUnavailableException.class);
    }

    @Test
    void resendRevokesPreviousTokenAndPreservesRecipient() {
        var previous = invitation("guest@example.com", NOW.plusSeconds(60));
        var replacement = invitation("guest@example.com", NOW.plusSeconds(604800));
        given(repository.findActiveBySpaceForUpdate(SPACE_ID)).willReturn(Optional.of(previous), Optional.of(replacement));

        var pending = service.prepareResend("admin@example.com");

        then(repository).should().revoke(INVITATION_ID, NOW);
        assertThat(pending.recipient()).isEqualTo("guest@example.com");
    }

    @Test
    void rejectsExpiredConsumedRevokedOrUnknownToken() {
        given(codec.hash("expired")).willReturn("expired-hash");
        given(repository.findByTokenHash("expired-hash"))
                .willReturn(Optional.of(invitation("guest@example.com", NOW)));
        given(codec.hash("unknown")).willReturn("unknown-hash");

        assertThatThrownBy(() -> service.preview("expired", null))
                .isInstanceOf(InvalidInvitationTokenException.class);
        assertThatThrownBy(() -> service.preview("unknown", null))
                .isInstanceOf(InvalidInvitationTokenException.class);
        assertThatThrownBy(() -> service.preview(" ", null))
                .isInstanceOf(InvalidInvitationTokenException.class);
    }

    @Test
    void acceptsNewAccountAndConfirmsItThroughInvitationPossession() {
        given(codec.hash("valid")).willReturn("valid-hash");
        given(repository.findByTokenHashForUpdate("valid-hash"))
                .willReturn(Optional.of(invitation("guest@example.com", NOW.plusSeconds(60))));
        given(hasher.hash(any(char[].class))).willReturn("{bcrypt}hash");
        var password = "senha segura 2026".toCharArray();

        service.accept(new InvitationAcceptanceCommand("valid", null, "Pessoa Convidada", password));

        then(repository).should().createGuestAccountAndMembership(
                GENERATED_ID, GENERATED_ID, SPACE_ID, "Pessoa Convidada", "guest@example.com", "{bcrypt}hash", NOW);
        then(repository).should().consume(INVITATION_ID, NOW);
        assertThat(password).containsOnly('\0');
    }

    @Test
    void existingAccountMustBeConfirmedLoggedInAsRecipientAndUnassociated() {
        var account = new InvitationAccount(GENERATED_ID, "guest@example.com", true, null);
        stubUsable(account);
        assertThatThrownBy(() -> service.accept(new InvitationAcceptanceCommand("valid", null, null, null)))
                .isInstanceOf(InvitationLoginRequiredException.class);

        stubUsable(account);
        assertThatThrownBy(() -> service.accept(new InvitationAcceptanceCommand(
                "valid", "other@example.com", null, null)))
                .isInstanceOf(InvitationIdentityMismatchException.class);

        stubUsable(new InvitationAccount(GENERATED_ID, "guest@example.com", true, UUID.randomUUID()));
        assertThatThrownBy(() -> service.accept(new InvitationAcceptanceCommand(
                "valid", "guest@example.com", null, null)))
                .isInstanceOf(InvitationTargetUnavailableException.class);

        stubUsable(account);
        service.accept(new InvitationAcceptanceCommand("valid", "GUEST@example.com", null, null));
        then(repository).should().createGuestMembership(GENERATED_ID, GENERATED_ID, SPACE_ID, NOW);
    }

    @Test
    void invitationPossessionConfirmsExistingUnconfirmedAccount() {
        stubUsable(new InvitationAccount(GENERATED_ID, "guest@example.com", false, null));

        service.accept(new InvitationAcceptanceCommand("valid", null, null, null));

        then(repository).should().confirmEmailAndCreateGuestMembership(
                GENERATED_ID, GENERATED_ID, SPACE_ID, NOW);
        then(repository).should().consume(INVITATION_ID, NOW);
    }

    private void stubUsable(InvitationAccount account) {
        given(codec.hash("valid")).willReturn("valid-hash");
        given(repository.findByTokenHashForUpdate("valid-hash"))
                .willReturn(Optional.of(invitation("guest@example.com", NOW.plusSeconds(60))));
        given(repository.findAccountByEmail("guest@example.com")).willReturn(Optional.of(account));
        given(repository.countActiveMembers(SPACE_ID)).willReturn(1);
    }

    private StoredInvitation invitation(String email, Instant expiresAt) {
        return new StoredInvitation(INVITATION_ID, SPACE_ID, "Casa", email, expiresAt, null, null, NOW.minusSeconds(1));
    }
}
