package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/** Header of the closing of one month: the version in force and when the month was first closed. */
public record StoredClosing(UUID id, YearMonth month, int currentVersion, Instant createdAt, Instant updatedAt) { }
