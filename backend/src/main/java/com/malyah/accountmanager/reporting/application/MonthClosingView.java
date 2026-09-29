package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;

/**
 * E07 view of one month by due date. {@code saved} is the stored snapshot of the current version (null while the
 * month is not closed); {@code current} is recalculated now from the current expenses with the same rules and is
 * never stored. {@code closable} tells whether the month accepts a closing (T28).
 */
public record MonthClosingView(String month, LocalDate periodStart, LocalDate periodEnd, String dateBasis,
        LocalDate today, String timeZone, boolean closable, ClosingSnapshotView saved, ClosingSnapshotView current) { }
