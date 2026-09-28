package com.malyah.accountmanager.recurrences.application;

public final class RecurrenceImpactChangedException extends RuntimeException {
    public RecurrenceImpactChangedException() {
        super("Os lançamentos ou previsões mudaram desde a prévia. Nada foi aplicado; revise o novo impacto antes de confirmar.");
    }
}
