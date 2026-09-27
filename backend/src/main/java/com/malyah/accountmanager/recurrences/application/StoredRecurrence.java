package com.malyah.accountmanager.recurrences.application;
import com.malyah.accountmanager.recurrences.domain.RecurrenceDefinition;
public record StoredRecurrence(RecurrenceDefinition definition, String categoryName,
        String responsibleDisplayName, String createdByDisplayName) { }
