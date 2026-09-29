package com.malyah.accountmanager.identity.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.malyah.accountmanager.identity.application.MembershipLifecycleEvent.Type;
import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.MembershipRepository;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.domain.EmailAddress;
import com.malyah.accountmanager.identity.domain.SpaceRole;

public final class MembershipManagementService {

    private final MembershipRepository repository;
    private final SessionRevoker sessionRevoker;
    private final IdentifierGenerator identifiers;
    private final Clock clock;
    private final java.util.List<MembershipDepartureHandler> departureHandlers;
    private final java.util.List<AdministrationTransferHandler> transferHandlers;

    public MembershipManagementService(
            MembershipRepository repository,
            SessionRevoker sessionRevoker,
            IdentifierGenerator identifiers,
            Clock clock) {
        this(repository, sessionRevoker, identifiers, clock, java.util.List.of());
    }

    public MembershipManagementService(
            MembershipRepository repository,
            SessionRevoker sessionRevoker,
            IdentifierGenerator identifiers,
            Clock clock,
            java.util.List<MembershipDepartureHandler> departureHandlers) {
        this(repository, sessionRevoker, identifiers, clock, departureHandlers, java.util.List.of());
    }

    public MembershipManagementService(
            MembershipRepository repository,
            SessionRevoker sessionRevoker,
            IdentifierGenerator identifiers,
            Clock clock,
            java.util.List<MembershipDepartureHandler> departureHandlers,
            java.util.List<AdministrationTransferHandler> transferHandlers) {
        this.repository = repository;
        this.sessionRevoker = sessionRevoker;
        this.identifiers = identifiers;
        this.clock = clock;
        this.departureHandlers = java.util.List.copyOf(departureHandlers);
        this.transferHandlers = java.util.List.copyOf(transferHandlers);
    }

    public List<ManagedMember> members(String rawActorEmail) {
        var actor = activeActor(rawActorEmail);
        return repository.findActiveMembers(actor.spaceId(), actor.userId());
    }

    public void remove(String rawActorEmail, UUID memberUserId) {
        var initialActor = administrator(rawActorEmail);
        activeMember(initialActor.spaceId(), memberUserId);
        repository.lockSpace(initialActor.spaceId());
        var actor = administrator(rawActorEmail);
        var member = memberAfterLock(actor.spaceId(), memberUserId);
        if (member.userId().equals(actor.userId()) || member.role() != SpaceRole.GUEST) {
            throw new MembershipConflictException("O administrador não pode remover a si próprio.");
        }
        var now = clock.instant();
        departureHandlers.forEach(handler -> handler.beforeMembershipEnds(
                actor.spaceId(), member.userId(), actor.userId(), now));
        repository.endMembership(actor.spaceId(), member.userId(), actor.userId(), "ADMIN_REMOVAL", now);
        repository.append(event(actor, member, Type.MEMBER_REMOVED, actor.role(), null, now));
        sessionRevoker.revokeAll(member.normalizedEmail());
    }

    public void leave(String rawActorEmail) {
        var initialActor = activeActor(rawActorEmail);
        repository.lockSpace(initialActor.spaceId());
        var actor = actorAfterLock(rawActorEmail);
        if (actor.role() == SpaceRole.ADMINISTRATOR) {
            throw new MembershipConflictException(
                    "Transfira a administração antes de sair. O encerramento do espaço ainda não está disponível.");
        }
        var now = clock.instant();
        departureHandlers.forEach(handler -> handler.beforeMembershipEnds(
                actor.spaceId(), actor.userId(), actor.userId(), now));
        repository.endMembership(actor.spaceId(), actor.userId(), actor.userId(), "VOLUNTARY_EXIT", now);
        repository.append(event(actor, actor, Type.MEMBER_LEFT, null, null, now));
        sessionRevoker.revokeAll(actor.normalizedEmail());
    }

    public void transferAdministration(String rawActorEmail, UUID newAdministratorUserId) {
        var initialActor = administrator(rawActorEmail);
        activeMember(initialActor.spaceId(), newAdministratorUserId);
        repository.lockSpace(initialActor.spaceId());
        var actor = administrator(rawActorEmail);
        var target = memberAfterLock(actor.spaceId(), newAdministratorUserId);
        if (target.userId().equals(actor.userId()) || target.role() != SpaceRole.GUEST) {
            throw new MembershipConflictException("Escolha o membro convidado ativo para receber a administração.");
        }
        var now = clock.instant();
        repository.transferAdministration(actor.spaceId(), actor.userId(), target.userId());
        repository.append(event(actor, target, Type.ADMINISTRATION_TRANSFERRED,
                SpaceRole.GUEST, SpaceRole.ADMINISTRATOR, now));
        transferHandlers.forEach(handler -> handler.afterAdministrationTransferred(
                actor.spaceId(), actor.userId(), target.userId(), now));
    }

    private MembershipActor administrator(String rawEmail) {
        var actor = activeActor(rawEmail);
        if (actor.role() != SpaceRole.ADMINISTRATOR) {
            throw new MembershipAdministratorRequiredException();
        }
        return actor;
    }

    private MembershipActor activeActor(String rawEmail) {
        var email = new EmailAddress(rawEmail).value();
        return repository.findActiveActor(email).orElseThrow(ManagedMemberNotFoundException::new);
    }

    private MembershipActor actorAfterLock(String rawEmail) {
        var email = new EmailAddress(rawEmail).value();
        return repository.findActiveActor(email).orElseThrow(() -> new MembershipConflictException(
                "A associação foi encerrada durante a operação. Atualize a sessão antes de tentar novamente."));
    }

    private MembershipActor activeMember(UUID spaceId, UUID userId) {
        if (userId == null) {
            throw new ManagedMemberNotFoundException();
        }
        return repository.findActiveMember(spaceId, userId).orElseThrow(ManagedMemberNotFoundException::new);
    }

    private MembershipActor memberAfterLock(UUID spaceId, UUID userId) {
        return repository.findActiveMember(spaceId, userId).orElseThrow(() -> new MembershipConflictException(
                "A associação ou o papel mudou durante a operação. Atualize os membros e tente novamente."));
    }

    private MembershipLifecycleEvent event(
            MembershipActor actor,
            MembershipActor subject,
            Type type,
            SpaceRole actorNewRole,
            SpaceRole subjectNewRole,
            java.time.Instant at) {
        return new MembershipLifecycleEvent(
                identifiers.next(), actor.spaceId(), actor.userId(), subject.userId(), type,
                actor.role(), actorNewRole, subject.role(), subjectNewRole, at);
    }
}
