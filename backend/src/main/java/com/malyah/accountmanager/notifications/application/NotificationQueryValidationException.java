package com.malyah.accountmanager.notifications.application;

/** Invalid notification list query (view, page or size). */
public final class NotificationQueryValidationException extends RuntimeException {
    private final String field;

    public NotificationQueryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
