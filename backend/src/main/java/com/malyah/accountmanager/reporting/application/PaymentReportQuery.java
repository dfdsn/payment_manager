package com.malyah.accountmanager.reporting.application;

import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;

/**
 * Filters and page of the payment view. The situation filter does not apply: the view holds active payments only,
 * so {@code filters.status()} must be absent.
 */
public record PaymentReportQuery(ReportFilters filters, int page, int size, PaymentSort sort,
        SortDirection direction) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAXIMUM_SIZE = 100;
}
