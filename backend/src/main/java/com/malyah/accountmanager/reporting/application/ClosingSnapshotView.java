package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Content of a closing: totals by due date, categories and entries (pending ones included) with the labels of the
 * moment. For a saved version it also names the version, its author and instant; for the current data those are
 * null and {@code businessDate} is today. Overdue values are projected on {@code businessDate}.
 */
public record ClosingSnapshotView(Integer version, UUID authorUserId, String authorDisplayName, Instant closedAt,
        LocalDate businessDate, String timeZone, boolean pendingAcknowledged, String contentDigest,
        DueIndicatorsView indicators, List<ClosingCategoryView> categories, List<ClosingLineView> lines) { }
