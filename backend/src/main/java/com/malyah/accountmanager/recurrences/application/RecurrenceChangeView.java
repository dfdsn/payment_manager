package com.malyah.accountmanager.recurrences.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Audit of an applied change or closure. */
public record RecurrenceChangeView(UUID id, String type, UUID actorUserId, String actorDisplayName, Instant occurredAt,
        long version, LocalDate effectiveDueDate, List<String> changedFields, String reason, int updatedCount,
        int removedCount, int reviewCount, int preservedCount) { }
