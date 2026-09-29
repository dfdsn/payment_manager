package com.malyah.accountmanager.notifications.application;

/** No summary with that identifier in the member's space (another space's summary is reported the same way). */
public final class ReminderSummaryNotFoundException extends RuntimeException {
    public ReminderSummaryNotFoundException() {
        super("Resumo não encontrado.");
    }
}
