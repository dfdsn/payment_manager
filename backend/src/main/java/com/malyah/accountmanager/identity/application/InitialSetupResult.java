package com.malyah.accountmanager.identity.application;

import java.util.UUID;

public record InitialSetupResult(
        UUID administratorId,
        String administratorName,
        String email,
        UUID spaceId,
        String spaceName,
        String currency,
        String locale,
        String timeZone) {
}
