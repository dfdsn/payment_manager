package com.malyah.accountmanager.identity.application;

public record InvitationPreview(
        String spaceName,
        boolean existingAccount,
        boolean loginRequired,
        boolean authenticatedAsInvitee) {
}
