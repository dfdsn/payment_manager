package com.malyah.accountmanager.identity.application;

public record PendingInvitationEmail(String recipient, String rawToken, String spaceName) {
}
