package com.malyah.accountmanager.recurrences.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * H06.3: a recurrence occurrence of the horizon that has no materialized expense yet. {@code dueDate} is the
 * projected due date, {@code estimated} marks a variable value still to be confirmed, and the category and
 * responsible are those the generation would use for the period.
 */
public record PlannedForecast(UUID recurrenceId, LocalDate dueDate, String description, BigDecimal amount,
        boolean estimated, UUID categoryId, String categoryName, UUID responsibleUserId,
        String responsibleDisplayName) { }
