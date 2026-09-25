package com.malyah.accountmanager.expenses.domain;

public final class ExpenseValidationException extends RuntimeException {
    private final String field;

    public ExpenseValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
