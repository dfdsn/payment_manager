package com.malyah.accountmanager.notifications.application;

/** H08.4: the test message cannot be sent now; {@code code} tells the screen what to fix. */
public final class WhatsAppTestUnavailableException extends RuntimeException {
    private final String code;

    public WhatsAppTestUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
