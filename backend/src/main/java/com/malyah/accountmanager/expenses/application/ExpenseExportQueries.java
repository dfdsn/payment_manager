package com.malyah.accountmanager.expenses.application;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * H06.4 public read contract of the expense list for the CSV export: the same selection predicate and the same
 * total order as {@code GET /expenses}, without pages. Callers count first and read inside one transaction.
 */
public interface ExpenseExportQueries {
    long count(UUID spaceId, ExpenseSelection selection);

    /** Streams at most {@code limit} rows in the list order; the rows are not kept in memory by the adapter. */
    void export(UUID spaceId, ExpenseSelection selection, ExpenseSort sort, SortDirection direction, int limit,
            Consumer<ExportedExpense> sink);
}
