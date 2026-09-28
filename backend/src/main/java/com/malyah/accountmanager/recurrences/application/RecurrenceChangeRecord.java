package com.malyah.accountmanager.recurrences.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceConfiguration;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSegment;

/** Everything persisted for a change or closure on the recurrence side, in one transaction. */
public record RecurrenceChangeRecord(UUID changeId, UUID recurrenceId, UUID spaceId, UUID actorId, String type,
        Instant at, long fromVersion, LocalDate effectiveDueDate, List<String> changedFields,
        String previousConfiguration, String newConfiguration, String reason, String impactHash, int updatedCount,
        int removedCount, int reviewCount, int preservedCount, List<RecurrenceSegment> segments,
        RecurrenceConfiguration currentConfiguration, LocalDate lastDueDate, Map<UUID, String> reviews) {
    public RecurrenceChangeRecord {
        changedFields = List.copyOf(changedFields);
        segments = List.copyOf(segments);
        reviews = Map.copyOf(reviews);
    }
}
