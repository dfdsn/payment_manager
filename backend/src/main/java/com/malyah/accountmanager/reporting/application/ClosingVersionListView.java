package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * H07.3: every version of a month, oldest first. {@code currentVersion} is null while the month is not closed;
 * each item says whether it is the version in force.
 */
public record ClosingVersionListView(String month, Integer currentVersion, List<Item> versions) {
    public record Item(int version, UUID authorUserId, String authorDisplayName, Instant closedAt,
            LocalDate businessDate, boolean pendingAcknowledged, DueIndicatorsView indicators, boolean current) {
        static Item of(VersionEntry entry, int currentVersion) {
            return new Item(entry.number(), entry.authorUserId(), entry.authorDisplayName(), entry.createdAt(),
                    entry.businessDate(), entry.pendingAcknowledged(), DueIndicatorsView.of(entry.indicators()),
                    entry.number() == currentVersion);
        }
    }
}
