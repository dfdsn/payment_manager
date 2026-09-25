package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

public record InvitationRegistration(
        UUID id,
        UUID spaceId,
        String invitedEmail,
        UUID invitedByUserId,
        String tokenHash,
        Instant expiresAt,
        Instant createdAt) {
}
