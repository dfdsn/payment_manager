package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseHistoryEvent(
        String type,
        UUID actorUserId,
        String actorDisplayName,
        Instant occurredAt,
        String reason,
        String notes,
        long version,
        String paidAmount,
        LocalDate paymentDate,
        UUID payerUserId,
        String payerDisplayName,
        String changedFields,
        UUID batchOperationId,
        java.util.List<ExpenseFieldChange> changes) {
    public ExpenseHistoryEvent(String type, UUID actorUserId, String actorDisplayName, Instant occurredAt,
            String reason, String notes, long version, String paidAmount, LocalDate paymentDate,
            UUID payerUserId, String payerDisplayName, String changedFields) {
        this(type, actorUserId, actorDisplayName, occurredAt, reason, notes, version, paidAmount,
                paymentDate, payerUserId, payerDisplayName, changedFields, null, java.util.List.of());
    }

    public ExpenseHistoryEvent(String type, UUID actorUserId, String actorDisplayName, Instant occurredAt,
            String reason, String notes, long version, String paidAmount, LocalDate paymentDate,
            UUID payerUserId, String payerDisplayName, String changedFields, UUID batchOperationId) {
        this(type, actorUserId, actorDisplayName, occurredAt, reason, notes, version, paidAmount,
                paymentDate, payerUserId, payerDisplayName, changedFields, batchOperationId, java.util.List.of());
    }
}
