package com.malyah.accountmanager.expenses.application;

public final class ExpenseNotFoundException extends RuntimeException {
    public ExpenseNotFoundException() { super("Despesa não encontrada neste espaço."); }
}
