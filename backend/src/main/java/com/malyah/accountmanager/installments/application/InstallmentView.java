package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * One installment n/N. In a preview only number, count, amount and due date are filled; for a created purchase the
 * remaining fields come from the installment's expense entry, which is where payment and cancellation happen.
 */
public record InstallmentView(int number, int count, String amount, LocalDate dueDate, UUID expenseId,
        ExpenseStatus status, Long version, boolean overdue, String description, UUID categoryId, String categoryName,
        UUID responsibleUserId, String responsibleDisplayName, LocalDate paymentDate, String paidAmount) {
    public InstallmentView(int number, int count, String amount, LocalDate dueDate, UUID expenseId,
            ExpenseStatus status) {
        this(number, count, amount, dueDate, expenseId, status, null, false, null, null, null, null, null, null, null);
    }
}
