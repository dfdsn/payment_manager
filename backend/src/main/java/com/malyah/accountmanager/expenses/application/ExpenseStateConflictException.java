package com.malyah.accountmanager.expenses.application;

public final class ExpenseStateConflictException extends RuntimeException {
    public ExpenseStateConflictException() {
        super("A despesa já foi quitada ou foi alterada. Atualize a lista e revise os dados.");
    }
}
