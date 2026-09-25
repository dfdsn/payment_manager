package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

public record CorrectExpenseCommand(
        UUID expenseId,
        long version,
        ExpenseStatus status,
        String description,
        String amount,
        LocalDate dueDate,
        String notes,
        String paidAmount,
        LocalDate paymentDate,
        UUID paidByUserId,
        String paymentNotes,
        UUID idempotencyKey) {
}
