package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Current state of a materialized recurring launch, read for a recurrence change (H04.5). */
public record RecurringOccurrenceSnapshot(UUID expenseId, LocalDate scheduledDueDate, long version, String status,
        boolean chargeConfirmed, BigDecimal amount, LocalDate dueDate, String description, UUID categoryId,
        UUID responsibleUserId, boolean dueDateCorrected) { }
