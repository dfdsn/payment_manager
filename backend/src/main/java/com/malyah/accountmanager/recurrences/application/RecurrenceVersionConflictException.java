package com.malyah.accountmanager.recurrences.application;

public final class RecurrenceVersionConflictException extends RuntimeException {
    public RecurrenceVersionConflictException() {
        super("A recorrência foi alterada por outra operação. Consulte a versão atual e revise o impacto novamente.");
    }
}
