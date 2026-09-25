package com.malyah.accountmanager.expenses.application;

public record StoredExpenseCreation(StoredExpense expense, boolean replayed) {
}
