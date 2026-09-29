package com.malyah.accountmanager.notifications.domain;

/** A value of the reminder settings that breaks a rule; {@code code} is the API error code. */
public final class NotificationValidationException extends RuntimeException {
    private final String code;
    private final String field;

    public NotificationValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
