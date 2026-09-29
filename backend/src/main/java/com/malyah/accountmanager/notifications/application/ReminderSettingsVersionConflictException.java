package com.malyah.accountmanager.notifications.application;

public final class ReminderSettingsVersionConflictException extends RuntimeException {
    public ReminderSettingsVersionConflictException() {
        super("As configurações foram alteradas. Atualize a tela antes de salvar.");
    }
}
