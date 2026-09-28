package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

public record CreateOneOffExpenseCommand(
        String description,
        String amount,
        ExpenseStatus status,
        LocalDate dueDate,
        LocalDate paymentDate,
        String notes,
        UUID idempotencyKey,
        String paidAmount, UUID paidByUserId, String paymentNotes, UUID categoryId, UUID responsibleUserId) {
    public CreateOneOffExpenseCommand(String description, String amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, String notes, UUID key) {
        this(description, amount, status, dueDate, paymentDate, notes, key, null, null, null, null, null);
    }
    public CreateOneOffExpenseCommand(String description, String amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, String notes, UUID key,
            String paidAmount, UUID paidByUserId, String paymentNotes) {
        this(description, amount, status, dueDate, paymentDate, notes, key, paidAmount, paidByUserId, paymentNotes, null, null);
    }

    public CreateOneOffExpenseCommand(String description, String amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, String notes, UUID key,
            String paidAmount, UUID paidByUserId, String paymentNotes, UUID categoryId) {
        this(description, amount, status, dueDate, paymentDate, notes, key, paidAmount, paidByUserId,
                paymentNotes, categoryId, null);
    }
}
