package com.malyah.accountmanager.expenses.application;

public record ExpenseListQuery(int page, int size, ExpenseSort sort, SortDirection direction) {
}
