package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Public contract used by the recurrences module to read and adjust its materialized launches (H04.5), so the
 * expense tables stay owned by the expenses module. Callers must hold the recurrence lock and run in a transaction.
 */
public interface RecurringOccurrenceAdjuster {
    /** Every materialized launch of the recurrence; launches from {@code lockFrom} on are locked for update. */
    List<RecurringOccurrenceSnapshot> occurrences(UUID spaceId, UUID recurrenceId, YearMonth lockFrom);

    /** Applies all adjustments or none; a launch that changed since it was read fails the whole operation. */
    void apply(UUID spaceId, UUID actorId, UUID changeId, Instant at, List<RecurringOccurrenceAdjustment> adjustments);
}
