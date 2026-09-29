package com.malyah.accountmanager.notifications.application;

/** Enabling or consenting needs a step first: {@code code} is WHATSAPP_RECIPIENT_REQUIRED or WHATSAPP_CONSENT_REQUIRED. */
public final class WhatsAppActivationRequiredException extends RuntimeException {
    public static final String RECIPIENT_REQUIRED = "WHATSAPP_RECIPIENT_REQUIRED";
    public static final String CONSENT_REQUIRED = "WHATSAPP_CONSENT_REQUIRED";
    private final String code;

    public WhatsAppActivationRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
