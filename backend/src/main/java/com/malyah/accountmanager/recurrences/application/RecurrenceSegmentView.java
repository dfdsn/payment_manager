package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

public record RecurrenceSegmentView(LocalDate effectiveMonth, String description, String amount,
        RecurrenceFrequency frequency, int dueDay, UUID categoryId, String categoryName, UUID responsibleUserId,
        String responsibleDisplayName) { }
