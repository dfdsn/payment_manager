package com.malyah.accountmanager.expenses.application;

import java.util.List;

public record ExpenseHistoryPage(
        List<ExpenseHistoryEvent> content,
        int page,
        int size,
        long totalElements,
        int totalPages) { }
