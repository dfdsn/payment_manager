package com.malyah.accountmanager.expenses.application;

import java.util.List;
import java.util.UUID;

/**
 * H06.3: public read contract of the materialized part of the future planning. The selection uses the due-date
 * (reference) basis and the {@code ACTIVE} situation, so cancelled expenses never reach it, and the same
 * {@link ExpenseSelection} predicate as the list and the E06 totals is applied inside the given space.
 */
public interface ExpensePlanningQueries {
    /** Exact sums of the whole selection grouped by month, origin, situation and confirmation. */
    List<PlanningExpenseBucket> planningTotals(UUID spaceId, ExpenseSelection selection);

    /** Number of selected expenses per reference date, so a page can be merged with forecasts by date. */
    List<ExpenseDayCount> dailyCounts(UUID spaceId, ExpenseSelection selection);

    /** A window of the selection ordered by reference date and id, the stable order of the planning. */
    List<PlanningExpense> planningEntries(UUID spaceId, ExpenseSelection selection, long offset, int limit);
}
