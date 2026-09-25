package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

public record StoredInvitation(
        UUID id,
        UUID spaceId,
        String spaceName,
        String invitedEmail,
        Instant expiresAt,
        Instant consumedAt,
        Instant revokedAt,
        Instant createdAt) {

    public boolean isUsableAt(Instant instant) {
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(instant);
    }
}
