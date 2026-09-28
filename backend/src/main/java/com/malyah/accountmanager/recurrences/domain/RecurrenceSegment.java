package com.malyah.accountmanager.recurrences.domain;

import java.time.YearMonth;
import java.util.Objects;

/**
 * Configuration valid from {@code effectiveMonth} until the next segment. The calendar of a segment is anchored
 * at its effective month. {@code estimateReset} marks an estimate explicitly set for this segment (RF-REC-10).
 */
public record RecurrenceSegment(YearMonth effectiveMonth, RecurrenceConfiguration configuration,
        boolean estimateReset) {
    public RecurrenceSegment {
        Objects.requireNonNull(effectiveMonth);
        Objects.requireNonNull(configuration);
    }

    RecurrenceSegment with(RecurrenceConfiguration value, boolean reset) {
        return new RecurrenceSegment(effectiveMonth, value, reset);
    }
}
