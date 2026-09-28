package com.malyah.accountmanager.expenses.application;

public interface RecurringExpenseMaterializer {
    boolean materialize(RecurringExpenseCommand command);
    java.util.UUID materializeAnticipated(AnticipatedRecurringExpenseCommand command);
}
