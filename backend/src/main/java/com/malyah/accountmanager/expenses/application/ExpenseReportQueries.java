package com.malyah.accountmanager.expenses.application;

import java.util.List;
import java.util.UUID;

/**
 * E06: public read contract through which the reporting module sums expenses without reading the expense tables
 * itself. It applies exactly the predicate of the expense list ({@link ExpenseSelection}) inside the given space;
 * the caller resolves the authorized space and the local business date.
 */
public interface ExpenseReportQueries {
    /** Groups every non-cancelled expense of the selection; cancelled entries never reach financial totals. */
    List<ExpenseTotalsBucket> totals(UUID spaceId, ExpenseSelection selection);
}
