package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

public record ExpenseView(
        UUID id,
        String origin,
        String description,
        String amount,
        String currency,
        ExpenseStatus status,
        LocalDate dueDate,
        LocalDate paymentDate,
        String paidAmount,
        LocalDate referenceDate,
        boolean overdue,
        String categoryName,
        UUID responsibleUserId,
        String notes,
        UUID createdByUserId,
        String createdByDisplayName,
        UUID paidByUserId,
        String paidByDisplayName,
        Instant createdAt,
        long version) {
}
