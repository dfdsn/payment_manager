package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.UUID;

/** RF-REC-16: ends the recurrence at the period of {@code lastDueDate}. Reason is required to apply. */
public record CloseRecurrenceCommand(UUID recurrenceId, long version, LocalDate lastDueDate, String reason,
        String impactToken, UUID idempotencyKey) { }
