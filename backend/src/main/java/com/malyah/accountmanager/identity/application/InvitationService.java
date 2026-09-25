package com.malyah.accountmanager.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import com.malyah.accountmanager.identity.application.port.AccessTokenCodec;
import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.InvitationRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.domain.EmailAddress;
import com.malyah.accountmanager.identity.domain.MemberName;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;
import com.malyah.accountmanager.identity.domain.SpaceRole;

public final class InvitationService {

    private static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

    private final InvitationRepository repository;
    private final AccessTokenCodec tokenCodec;
    private final IdentifierGenerator identifierGenerator;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public InvitationService(
            InvitationRepository repository,
            AccessTokenCodec tokenCodec,
            IdentifierGenerator identifierGenerator,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            Clock clock) {
        this.repository = repository;
        this.tokenCodec = tokenCodec;
        this.identifierGenerator = identifierGenerator;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    public Optional<InvitationStatus> current(String actorEmail) {
        var actor = administrator(actorEmail);
        var now = clock.instant();
        return repository.findActiveBySpaceForUpdate(actor.spaceId())
                .filter(invitation -> invitation.isUsableAt(now))
                .map(invitation -> new InvitationStatus(invitation.invitedEmail(), invitation.expiresAt()));
    }

    public PendingInvitationEmail prepareNew(String actorEmail, String rawInvitedEmail) {
        var actor = administrator(actorEmail);
        var invitedEmail = new EmailAddress(rawInvitedEmail).value();
        var now = clock.instant();
        repository.lockSpace(actor.spaceId());
        ensureSpaceHasVacancy(actor.spaceId());
        ensureTargetAvailable(invitedEmail);

        var current = repository.findActiveBySpaceForUpdate(actor.spaceId());
        if (current.isPresent()) {
            if (current.get().isUsableAt(now)) {
                throw new InvitationAlreadyPendingException();
            }
            repository.revoke(current.get().id(), now);
        }
        return create(actor, invitedEmail, now);
    }

    public PendingInvitationEmail prepareResend(String actorEmail) {
        var actor = administrator(actorEmail);
        var now = clock.instant();
        repository.lockSpace(actor.spaceId());
        ensureSpaceHasVacancy(actor.spaceId());
        var current = repository.findActiveBySpaceForUpdate(actor.spaceId())
                .filter(invitation -> invitation.isUsableAt(now))
                .orElseThrow(NoPendingInvitationException::new);
        ensureTargetAvailable(current.invitedEmail());
        repository.revoke(current.id(), now);
        return create(actor, current.invitedEmail(), now);
    }

    public void revoke(String actorEmail) {
        var actor = administrator(actorEmail);
        repository.lockSpace(actor.spaceId());
        repository.findActiveBySpaceForUpdate(actor.spaceId())
                .ifPresent(invitation -> repository.revoke(invitation.id(), clock.instant()));
    }

    public InvitationPreview preview(String rawToken, String authenticatedEmail) {
        var invitation = usable(rawToken, false);
        var account = repository.findAccountByEmail(invitation.invitedEmail());
        var authenticatedAsInvitee = authenticatedEmail != null
                && invitation.invitedEmail().equals(new EmailAddress(authenticatedEmail).value());
        return new InvitationPreview(
                invitation.spaceName(),
                account.isPresent(),
                account.filter(InvitationAccount::emailConfirmed).isPresent(),
                authenticatedAsInvitee);
    }

    public void accept(InvitationAcceptanceCommand command) {
        var invitation = usable(command.rawToken(), true);
        var now = clock.instant();
        repository.lockSpace(invitation.spaceId());
        ensureSpaceHasVacancy(invitation.spaceId());

        var account = repository.findAccountByEmail(invitation.invitedEmail());
        if (account.isPresent()) {
            acceptExisting(command, invitation, account.get(), now);
        } else {
            acceptNew(command, invitation, now);
        }
        repository.consume(invitation.id(), now);
    }

    private void acceptExisting(
            InvitationAcceptanceCommand command,
            StoredInvitation invitation,
            InvitationAccount account,
            Instant now) {
        if (!account.emailConfirmed()) {
            if (command.authenticatedEmail() != null && !command.authenticatedEmail().isBlank()) {
                throw new InvitationIdentityMismatchException();
            }
            if (account.hasActiveMembership()) {
                throw new InvitationTargetUnavailableException();
            }
            repository.confirmEmailAndCreateGuestMembership(
                    identifierGenerator.next(), account.userId(), invitation.spaceId(), now);
            return;
        }
        if (command.authenticatedEmail() == null || command.authenticatedEmail().isBlank()) {
            throw new InvitationLoginRequiredException();
        }
        var authenticatedEmail = new EmailAddress(command.authenticatedEmail()).value();
        if (!invitation.invitedEmail().equals(authenticatedEmail)) {
            throw new InvitationIdentityMismatchException();
        }
        if (account.hasActiveMembership()) {
            throw new InvitationTargetUnavailableException();
        }
        repository.createGuestMembership(
                identifierGenerator.next(), account.userId(), invitation.spaceId(), now);
    }

    private void acceptNew(InvitationAcceptanceCommand command, StoredInvitation invitation, Instant now) {
        if (command.authenticatedEmail() != null && !command.authenticatedEmail().isBlank()) {
            throw new InvitationIdentityMismatchException();
        }
        var displayName = new MemberName(command.displayName()).value();
        var password = command.password();
        try {
            passwordPolicy.validate(password);
            repository.createGuestAccountAndMembership(
                    identifierGenerator.next(),
                    identifierGenerator.next(),
                    invitation.spaceId(),
                    displayName,
                    invitation.invitedEmail(),
                    passwordHasher.hash(password),
                    now);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private InvitationActor administrator(String rawEmail) {
        var email = new EmailAddress(rawEmail).value();
        return repository.findActorByEmail(email)
                .filter(actor -> actor.role() == SpaceRole.ADMINISTRATOR)
                .orElseThrow(InvitationAdministratorRequiredException::new);
    }

    private void ensureSpaceHasVacancy(java.util.UUID spaceId) {
        if (repository.countActiveMembers(spaceId) >= 2) {
            throw new SpaceMemberLimitReachedException();
        }
    }

    private void ensureTargetAvailable(String normalizedEmail) {
        repository.findAccountByEmail(normalizedEmail)
                .filter(InvitationAccount::hasActiveMembership)
                .ifPresent(account -> {
                    throw new InvitationTargetUnavailableException();
                });
    }

    private PendingInvitationEmail create(InvitationActor actor, String invitedEmail, Instant now) {
        var rawToken = tokenCodec.generate();
        var invitation = new InvitationRegistration(
                identifierGenerator.next(),
                actor.spaceId(),
                invitedEmail,
                actor.userId(),
                tokenCodec.hash(rawToken),
                now.plus(INVITATION_VALIDITY),
                now);
        repository.store(invitation);
        var stored = repository.findActiveBySpaceForUpdate(actor.spaceId()).orElseThrow();
        return new PendingInvitationEmail(invitedEmail, rawToken, stored.spaceName());
    }

    private StoredInvitation usable(String rawToken, boolean forUpdate) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            throw new InvalidInvitationTokenException();
        }
        var hash = tokenCodec.hash(rawToken);
        var invitation = forUpdate
                ? repository.findByTokenHashForUpdate(hash)
                : repository.findByTokenHash(hash);
        return invitation.filter(value -> value.isUsableAt(clock.instant()))
                .orElseThrow(InvalidInvitationTokenException::new);
    }
}
