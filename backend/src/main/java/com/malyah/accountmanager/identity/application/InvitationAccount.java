package com.malyah.accountmanager.identity.application;

import java.util.UUID;

public record InvitationAccount(
        UUID userId,
        String normalizedEmail,
        boolean emailConfirmed,
        UUID activeSpaceId) {

    public boolean hasActiveMembership() {
        return activeSpaceId != null;
    }
}
