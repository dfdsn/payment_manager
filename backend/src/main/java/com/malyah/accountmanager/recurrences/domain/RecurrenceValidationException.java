package com.malyah.accountmanager.recurrences.domain;

public final class RecurrenceValidationException extends RuntimeException {
    private final String field;
    public RecurrenceValidationException(String field, String message) { super(message); this.field = field; }
    public String field() { return field; }
}
