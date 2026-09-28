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
        long version, PaymentAudit paymentAudit, UUID categoryId, String categoryName,
        UUID responsibleUserId, String responsibleDisplayName, String origin, boolean chargeConfirmed,
        ChargeConfirmationAudit chargeConfirmation) {
    public StoredExpense(UUID id, UUID spaceId, String description, BigDecimal amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, BigDecimal paidAmount, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt,
            long version, PaymentAudit paymentAudit, UUID categoryId, String categoryName,
            UUID responsibleUserId, String responsibleDisplayName, String origin, boolean chargeConfirmed) {
        this(id, spaceId, description, amount, status, dueDate, paymentDate, paidAmount, notes, createdByUserId,
                createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version, paymentAudit,
                categoryId, categoryName, responsibleUserId, responsibleDisplayName, origin, chargeConfirmed, null);
    }
    public StoredExpense(UUID id, UUID spaceId, String description, BigDecimal amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, BigDecimal paidAmount, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt,
            long version, PaymentAudit paymentAudit, UUID categoryId, String categoryName,
            UUID responsibleUserId, String responsibleDisplayName) {
        this(id, spaceId, description, amount, status, dueDate, paymentDate, paidAmount, notes, createdByUserId,
                createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version, paymentAudit,
                categoryId, categoryName, responsibleUserId, responsibleDisplayName, "ONE_OFF", true);
    }
    public StoredExpense(UUID id, UUID spaceId, String description, BigDecimal amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, BigDecimal paidAmount, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt, long version) {
        this(id, spaceId, description, amount, status, dueDate, paymentDate, paidAmount, notes, createdByUserId,
                createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version, null, null, null, null, null,
                "ONE_OFF", true);
    }
    public StoredExpense(UUID id, UUID spaceId, String description, BigDecimal amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, BigDecimal paidAmount, String notes, UUID createdByUserId,
            String createdByDisplayName, UUID paidByUserId, String paidByDisplayName, Instant createdAt,
            long version, PaymentAudit paymentAudit) {
        this(id, spaceId, description, amount, status, dueDate, paymentDate, paidAmount, notes, createdByUserId,
                createdByDisplayName, paidByUserId, paidByDisplayName, createdAt, version, paymentAudit, null, null, null, null,
                "ONE_OFF", true);
    }
}
