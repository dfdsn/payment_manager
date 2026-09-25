package com.malyah.accountmanager.expenses.application;

public record ExpenseCreationResult(ExpenseView expense, boolean replayed) {
}
