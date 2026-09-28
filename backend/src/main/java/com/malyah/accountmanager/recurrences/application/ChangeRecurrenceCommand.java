package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

/**
 * "Este e os próximos" (RF-REC-14): the full configuration wanted from the period of {@code effectiveDueDate} on.
 * Only fields that differ from the configuration of that period are applied. Version, impact token and key are
 * required to apply and ignored by the preview.
 */
public record ChangeRecurrenceCommand(UUID recurrenceId, long version, LocalDate effectiveDueDate, String description,
        String amount, RecurrenceFrequency frequency, Integer dueDay, UUID categoryId, UUID responsibleUserId,
        String impactToken, UUID idempotencyKey) { }
