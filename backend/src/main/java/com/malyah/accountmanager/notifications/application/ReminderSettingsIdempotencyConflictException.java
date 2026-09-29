package com.malyah.accountmanager.notifications.application;

public final class ReminderSettingsIdempotencyConflictException extends RuntimeException {
    public ReminderSettingsIdempotencyConflictException() {
        super("A chave de repetição já foi usada com outro pedido.");
    }
}
