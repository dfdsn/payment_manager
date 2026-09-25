package com.malyah.accountmanager.expenses.application;

import java.util.List;

public record ExpensePage(
        List<ExpenseView> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        ExpenseSort sort,
        SortDirection direction) {
}
