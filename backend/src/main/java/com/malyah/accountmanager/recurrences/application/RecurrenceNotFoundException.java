package com.malyah.accountmanager.recurrences.application;

public final class RecurrenceNotFoundException extends RuntimeException {
    public RecurrenceNotFoundException() { super("Recorrência não encontrada neste espaço."); }
}
