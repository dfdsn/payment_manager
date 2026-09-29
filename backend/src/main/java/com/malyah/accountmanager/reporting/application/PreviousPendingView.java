package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;

/**
 * RF-REL-03: entries still pending whose due date is before the selected month. Shown apart from the month and
 * never added to its totals; {@code overdue*} is a subset of the pending values.
 */
public record PreviousPendingView(LocalDate dueBefore, long count, String total, String estimated,
        long overdueCount, String overdueTotal) { }
