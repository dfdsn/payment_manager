package com.malyah.accountmanager.identity.application;

import java.time.Instant;

public record InvitationStatus(String invitedEmail, Instant expiresAt) {
}
