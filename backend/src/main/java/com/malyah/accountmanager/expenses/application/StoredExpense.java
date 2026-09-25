package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

public record StoredExpense(
        UUID id,
        UUID spaceId,
        String description,
        BigDecimal amount,
        ExpenseStatus status,
        LocalDate dueDate,
        LocalDate paymentDate,
        BigDecimal paidAmount,
        String notes,
        UUID createdByUserId,
        String createdByDisplayName,
        UUID paidByUserId,
        String paidByDisplayName,
        Instant createdAt,
        long version) {
}
