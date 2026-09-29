package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.SortDirection;

/** H06.4: the filters and order of {@code GET /expenses}, without the page. */
public record ExpenseExportQuery(String search, LocalDate dateFrom, LocalDate dateTo, ExpenseDateBasis dateBasis,
        UUID categoryId, boolean withoutCategory, UUID responsibleUserId, boolean withoutResponsible,
        UUID payerUserId, ExpenseStatusFilter status, ExpenseSort sort, SortDirection direction) { }
