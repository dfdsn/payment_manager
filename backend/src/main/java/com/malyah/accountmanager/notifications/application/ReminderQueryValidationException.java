package com.malyah.accountmanager.notifications.application;

/** Invalid preview or summary query (date, slot, identifier). */
public final class ReminderQueryValidationException extends RuntimeException {
    private final String field;

    public ReminderQueryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
