package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

public record StoredAccessToken(
        UUID id,
        UUID userId,
        String normalizedEmail,
        AccessTokenPurpose purpose,
        Instant expiresAt,
        Instant consumedAt,
        Instant revokedAt) {

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }
}
