package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * H08.2: public read contract of the expenses a reminder may mention. Only {@code PENDING} expenses of the space
 * with a due date up to {@code dueThrough} are returned (overdue ones included), so paid and cancelled expenses
 * never reach a summary. The state is read as it is now, inside the caller's transaction.
 */
public interface ExpenseReminderQueries {
    List<ReminderExpense> pendingDueThrough(UUID spaceId, LocalDate dueThrough);

    /**
     * H08.3: the current status and due date of the given expenses of the space, whatever their status; ids of
     * another space are simply absent.
     */
    List<ReminderExpenseState> currentStates(UUID spaceId, Collection<UUID> expenseIds);
}
