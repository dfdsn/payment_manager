package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.YearMonth;

/** The version in force of one closed month, as the annual list shows it. */
public record ClosingHead(YearMonth month, int currentVersion, String authorDisplayName, Instant createdAt,
        String contentDigest) { }
