package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;

/**
 * Filters of the reports, the same as the H03.4 expense list except the period, which is always one calendar month
 * of the chosen date basis. {@code month} is {@code AAAA-MM}; absent means the current month in the space time zone.
 */
public record ReportFilters(String month, String search, UUID categoryId, boolean withoutCategory,
        UUID responsibleUserId, boolean withoutResponsible, UUID payerUserId, ExpenseStatusFilter status) {
    public static ReportFilters currentMonth() {
        return new ReportFilters(null, null, null, false, null, false, null, null);
    }
}
