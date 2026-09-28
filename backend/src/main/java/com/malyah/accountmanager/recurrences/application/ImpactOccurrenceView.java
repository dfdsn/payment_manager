package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Effect on one materialized launch: UPDATE, REMOVE (leaves the active program), REVIEW or PRESERVE. */
public record ImpactOccurrenceView(UUID expenseId, LocalDate scheduledDueDate, LocalDate dueDate, String description,
        String amount, String status, boolean chargeConfirmed, String action, String reason,
        List<ImpactFieldChange> changes, List<String> preservedFields) { }
