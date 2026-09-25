package com.malyah.accountmanager.expenses.application;

public final class ExpenseQueryValidationException extends RuntimeException {
    private final String field;

    public ExpenseQueryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
