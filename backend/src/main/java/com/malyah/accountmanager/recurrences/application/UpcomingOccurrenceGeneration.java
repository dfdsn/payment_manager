package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * H08.2 / RF-REC-07: materializes ahead of time, through the H04.2 generation queue and materializer, every
 * occurrence of the space's recurrences due from the current local month up to {@code dueThrough}, even when it
 * belongs to the next month (due on 03/10, materialized by 28/09). The unique period of an occurrence keeps a
 * concurrent periodic generation or anticipation from creating a second expense. Each occurrence commits in its
 * own transaction, so the caller must not hold one.
 */
public interface UpcomingOccurrenceGeneration {
    /** Returns how many occurrences were materialized by this call. */
    int materializeUpcoming(UUID spaceId, LocalDate dueThrough);
}
