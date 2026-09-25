package com.malyah.accountmanager.identity.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.identity.application.InvitationAccount;
import com.malyah.accountmanager.identity.application.InvitationActor;
import com.malyah.accountmanager.identity.application.InvitationRegistration;
import com.malyah.accountmanager.identity.application.StoredInvitation;

public interface InvitationRepository {

    Optional<InvitationActor> findActorByEmail(String normalizedEmail);

    void lockSpace(UUID spaceId);

    int countActiveMembers(UUID spaceId);

    Optional<StoredInvitation> findActiveBySpaceForUpdate(UUID spaceId);

    Optional<StoredInvitation> findByTokenHash(String tokenHash);

    Optional<StoredInvitation> findByTokenHashForUpdate(String tokenHash);

    Optional<InvitationAccount> findAccountByEmail(String normalizedEmail);

    void store(InvitationRegistration registration);

    void revoke(UUID invitationId, Instant revokedAt);

    void createGuestAccountAndMembership(
            UUID userId,
            UUID membershipId,
            UUID spaceId,
            String displayName,
            String normalizedEmail,
            String passwordHash,
            Instant createdAt);

    void createGuestMembership(UUID membershipId, UUID userId, UUID spaceId, Instant createdAt);

    void confirmEmailAndCreateGuestMembership(UUID membershipId, UUID userId, UUID spaceId, Instant createdAt);

    void consume(UUID invitationId, Instant consumedAt);
}
