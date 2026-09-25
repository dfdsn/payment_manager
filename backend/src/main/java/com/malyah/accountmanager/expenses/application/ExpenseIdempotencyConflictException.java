package com.malyah.accountmanager.expenses.application;

public final class ExpenseIdempotencyConflictException extends RuntimeException {
    public ExpenseIdempotencyConflictException() {
        super("A chave de repetição já foi usada com dados diferentes.");
    }
}
