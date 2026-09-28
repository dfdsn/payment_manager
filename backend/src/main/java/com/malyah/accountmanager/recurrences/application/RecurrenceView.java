package com.malyah.accountmanager.recurrences.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;

public record RecurrenceView(UUID id, String description, String amount, RecurrenceValueType valueType,
        RecurrenceFrequency frequency, LocalDate firstDueDate, LocalDate lastDueDate, int baseDay,
        UUID categoryId, String categoryName, UUID responsibleUserId, String responsibleDisplayName,
        UUID createdByUserId, String createdByDisplayName, Instant createdAt, long version,
        List<LocalDate> previewDates) { }
