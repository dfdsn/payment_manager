package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

public record ExpenseListQuery(int page, int size, ExpenseSort sort, SortDirection direction,
        String search, LocalDate dateFrom, LocalDate dateTo, ExpenseDateBasis dateBasis,
        UUID categoryId, boolean withoutCategory, UUID responsibleUserId, boolean withoutResponsible,
        UUID payerUserId, ExpenseStatusFilter status, LocalDate today) {
    public ExpenseListQuery(int page, int size, ExpenseSort sort, SortDirection direction) {
        this(page, size, sort, direction, null, null, null, ExpenseDateBasis.DUE_DATE,
                null, false, null, false, null, ExpenseStatusFilter.ACTIVE, null);
    }

    public ExpenseSelection selection() {
        return new ExpenseSelection(search, dateFrom, dateTo, dateBasis, categoryId, withoutCategory,
                responsibleUserId, withoutResponsible, payerUserId, status, today);
    }
}
