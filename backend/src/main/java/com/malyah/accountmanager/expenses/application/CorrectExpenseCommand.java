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
        UUID idempotencyKey,
        UUID categoryId,
        UUID responsibleUserId) {
    public CorrectExpenseCommand(UUID expenseId, long version, ExpenseStatus status, String description,
            String amount, LocalDate dueDate, String notes, String paidAmount, LocalDate paymentDate,
            UUID paidByUserId, String paymentNotes, UUID idempotencyKey) {
        this(expenseId, version, status, description, amount, dueDate, notes, paidAmount, paymentDate,
                paidByUserId, paymentNotes, idempotencyKey, null, null);
    }

    public CorrectExpenseCommand(UUID expenseId, long version, ExpenseStatus status, String description,
            String amount, LocalDate dueDate, String notes, String paidAmount, LocalDate paymentDate,
            UUID paidByUserId, String paymentNotes, UUID idempotencyKey, UUID categoryId) {
        this(expenseId, version, status, description, amount, dueDate, notes, paidAmount, paymentDate,
                paidByUserId, paymentNotes, idempotencyKey, categoryId, null);
    }
}
