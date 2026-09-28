package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Impact shown before a change or closure. {@code impactToken} identifies exactly this impact; applying with a token
 * that no longer matches the current data is rejected instead of applying a different set of changes.
 */
public record RecurrenceImpactView(UUID recurrenceId, String operation, long version, LocalDate effectiveDueDate,
        List<String> changedFields, String impactToken, List<ImpactOccurrenceView> occurrences,
        List<ImpactForecastView> forecasts, int updatedCount, int removedCount, int reviewCount, int preservedCount) { }
