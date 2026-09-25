package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

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
        long version, PaymentAudit paymentAudit, List<ExpenseHistoryEvent> history) {
    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID responsibleUserId,
            String notes, UUID createdByUserId, String createdByDisplayName, UUID paidByUserId,
            String paidByDisplayName, Instant createdAt, long version) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, responsibleUserId, notes, createdByUserId, createdByDisplayName,
                paidByUserId, paidByDisplayName, createdAt, version, null, List.of());
    }

    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID responsibleUserId,
            String notes, UUID createdByUserId, String createdByDisplayName, UUID paidByUserId,
            String paidByDisplayName, Instant createdAt, long version, PaymentAudit paymentAudit) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, responsibleUserId, notes, createdByUserId, createdByDisplayName,
                paidByUserId, paidByDisplayName, createdAt, version, paymentAudit, List.of());
    }
}
