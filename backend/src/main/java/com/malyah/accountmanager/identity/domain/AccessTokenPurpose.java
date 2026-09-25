package com.malyah.accountmanager.identity.domain;

import java.time.Duration;

public enum AccessTokenPurpose {
    CONFIRM_EMAIL(Duration.ofHours(24)),
    RESET_PASSWORD(Duration.ofMinutes(30));

    private final Duration validity;

    AccessTokenPurpose(Duration validity) {
        this.validity = validity;
    }

    public Duration validity() {
        return validity;
    }
}
