package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringExpenseCommand(UUID jobId, UUID leaseToken, UUID occurrenceId, UUID recurrenceId,
        UUID spaceId, String description, BigDecimal amount, boolean chargeConfirmed, LocalDate scheduledDueDate,
        UUID categoryId, UUID responsibleUserId, UUID createdByUserId, Instant generatedAt) { }
