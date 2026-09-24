package com.malyah.accountmanager.identity.application;

import java.util.UUID;

import com.malyah.accountmanager.identity.domain.SpaceRole;

public record AuthenticatedUserContext(
        UUID userId,
        String displayName,
        String email,
        UUID spaceId,
        String spaceName,
        SpaceRole role,
        String currency,
        String locale,
        String timeZone) {
}
