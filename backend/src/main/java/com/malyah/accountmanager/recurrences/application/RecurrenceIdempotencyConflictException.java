package com.malyah.accountmanager.recurrences.application;
public final class RecurrenceIdempotencyConflictException extends RuntimeException {
    public RecurrenceIdempotencyConflictException() { super("A chave de repetição já foi usada com dados diferentes."); }
}
