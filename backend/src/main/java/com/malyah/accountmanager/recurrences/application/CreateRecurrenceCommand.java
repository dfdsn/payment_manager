package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;

public record CreateRecurrenceCommand(String description, String amount, RecurrenceValueType valueType,
        RecurrenceFrequency frequency, LocalDate firstDueDate, LocalDate lastDueDate,
        UUID categoryId, UUID responsibleUserId, UUID idempotencyKey) { }
