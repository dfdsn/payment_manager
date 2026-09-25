package com.malyah.accountmanager.identity.application;

import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

public record PendingAccessEmail(String recipient, String rawToken, AccessTokenPurpose purpose) {
}
