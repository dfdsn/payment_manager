package com.malyah.accountmanager.identity.application;

public record InvitationAcceptanceCommand(
        String rawToken,
        String authenticatedEmail,
        String displayName,
        char[] password) {
}
