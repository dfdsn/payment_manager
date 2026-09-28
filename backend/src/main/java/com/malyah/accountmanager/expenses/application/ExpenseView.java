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
        UUID categoryId,
        UUID responsibleUserId,
        String responsibleDisplayName,
        String notes,
        UUID createdByUserId,
        String createdByDisplayName,
        UUID paidByUserId,
        String paidByDisplayName,
        Instant createdAt,
        long version, PaymentAudit paymentAudit, List<ExpenseHistoryEvent> history,
        boolean chargeConfirmed, ChargeConfirmationView chargeConfirmation) {
    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID categoryId,
            UUID responsibleUserId, String responsibleDisplayName, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt,
            long version, PaymentAudit paymentAudit, List<ExpenseHistoryEvent> history, boolean chargeConfirmed) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, categoryId, responsibleUserId, responsibleDisplayName, notes,
                createdByUserId, createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version,
                paymentAudit, history, chargeConfirmed, null);
    }
    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID categoryId,
            UUID responsibleUserId, String responsibleDisplayName, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt,
            long version, PaymentAudit paymentAudit, List<ExpenseHistoryEvent> history) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, categoryId, responsibleUserId, responsibleDisplayName, notes,
                createdByUserId, createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version,
                paymentAudit, history, true);
    }
    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID responsibleUserId,
            String notes, UUID createdByUserId, String createdByDisplayName, UUID paidByUserId,
            String paidByDisplayName, Instant createdAt, long version) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, null, responsibleUserId, null, notes, createdByUserId, createdByDisplayName,
                paidByUserId, paidByDisplayName, createdAt, version, null, List.of(), true);
    }

    public ExpenseView(UUID id, String origin, String description, String amount, String currency,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, String paidAmount,
            LocalDate referenceDate, boolean overdue, String categoryName, UUID responsibleUserId,
            String notes, UUID createdByUserId, String createdByDisplayName, UUID paidByUserId,
            String paidByDisplayName, Instant createdAt, long version, PaymentAudit paymentAudit) {
        this(id, origin, description, amount, currency, status, dueDate, paymentDate, paidAmount, referenceDate,
                overdue, categoryName, null, responsibleUserId, null, notes, createdByUserId, createdByDisplayName,
                paidByUserId, paidByDisplayName, createdAt, version, paymentAudit, List.of(), true);
    }
}
