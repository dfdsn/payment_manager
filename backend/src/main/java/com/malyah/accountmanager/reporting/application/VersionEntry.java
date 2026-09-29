package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.reporting.domain.DueIndicators;

/** One stored version of a month without its categories and lines, as the list of versions shows it. */
public record VersionEntry(int number, UUID authorUserId, String authorDisplayName, Instant createdAt,
        LocalDate businessDate, boolean pendingAcknowledged, DueIndicators indicators) { }
