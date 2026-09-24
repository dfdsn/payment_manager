package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

public record AccessTokenRegistration(
        UUID id,
        UUID userId,
        AccessTokenPurpose purpose,
        String tokenHash,
        Instant expiresAt,
        Instant createdAt) {
}
