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
        String changedFields) { }
