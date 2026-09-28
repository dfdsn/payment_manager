package com.malyah.accountmanager.recurrences.application;

import java.time.Instant;
import java.util.List;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSchedule;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSegment;

/** A definition with its segments and closure data (H04.5). */
public record StoredSchedule(StoredRecurrence recurrence, List<RecurrenceSegment> segments,
        List<RecurrenceSegmentView> segmentViews, Instant closedAt, String closureReason, String closedByDisplayName) {
    public StoredSchedule {
        segments = List.copyOf(segments);
        segmentViews = List.copyOf(segmentViews);
    }

    public RecurrenceSchedule schedule() {
        var d = recurrence.definition();
        return RecurrenceSchedule.of(d.firstDueDate(), d.lastDueDate(), segments);
    }
}
