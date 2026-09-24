package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

public record InitialSetupRegistration(
        UUID administratorId,
        String administratorName,
        String normalizedEmail,
        String passwordHash,
        UUID spaceId,
        String spaceName,
        String currency,
        String locale,
        String timeZone,
        UUID membershipId,
        Instant createdAt) {
}
