package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;

/**
 * H06.1 dashboard of one month by due date. {@code dateBasis} is always {@code DUE_DATE}: a paid entry without due
 * date is placed by its payment date, as its reference date. {@code today} and {@code timeZone} make the overdue
 * projection explicit.
 */
public record DueDashboardView(String month, LocalDate periodStart, LocalDate periodEnd, String dateBasis,
        LocalDate today, String timeZone, DueIndicatorsView indicators, PreviousPendingView previousPending) { }
