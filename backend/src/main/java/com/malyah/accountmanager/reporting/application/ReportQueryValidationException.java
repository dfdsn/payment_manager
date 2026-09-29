package com.malyah.accountmanager.reporting.application;

public final class ReportQueryValidationException extends RuntimeException {
    private final String field;

    public ReportQueryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
