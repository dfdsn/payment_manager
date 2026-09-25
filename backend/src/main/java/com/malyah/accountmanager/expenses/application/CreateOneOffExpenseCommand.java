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
        UUID idempotencyKey) {
}
