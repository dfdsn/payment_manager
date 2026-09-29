package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.reporting.domain.ClosingCategory;
import com.malyah.accountmanager.reporting.domain.ClosingLine;
import com.malyah.accountmanager.reporting.domain.ClosingSummary;
import com.malyah.accountmanager.reporting.domain.DueIndicators;

/**
 * One immutable version of a month closing as stored: author, instant, the business date and time zone of the
 * overdue projection, and the content (totals, categories and lines) exactly as saved.
 */
public record ClosingVersion(UUID id, UUID closingId, YearMonth month, int number, UUID authorUserId,
        String authorDisplayName, Instant createdAt, LocalDate businessDate, String timeZone,
        boolean pendingAcknowledged, String contentDigest, DueIndicators indicators, List<ClosingCategory> categories,
        List<ClosingLine> lines) {
    public ClosingVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(closingId, "closingId");
        Objects.requireNonNull(month, "month");
        if (number < 1) throw new IllegalArgumentException("A versão começa em 1.");
        categories = List.copyOf(categories);
        lines = List.copyOf(lines);
    }

    static ClosingVersion of(UUID id, UUID closingId, YearMonth month, int number, UUID authorUserId,
            String authorDisplayName, Instant createdAt, LocalDate businessDate, String timeZone,
            boolean pendingAcknowledged, ClosingSummary summary) {
        return new ClosingVersion(id, closingId, month, number, authorUserId, authorDisplayName, createdAt,
                businessDate, timeZone, pendingAcknowledged, summary.digest(), summary.indicators(),
                summary.categories(), summary.lines());
    }
}
