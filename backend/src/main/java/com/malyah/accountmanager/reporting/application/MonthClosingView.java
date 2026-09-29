package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.List;

/**
 * E07 view of one month by due date. {@code saved} is the stored snapshot of the current version (null while the
 * month is not closed); {@code current} is recalculated now from the current expenses with the same rules and is
 * never stored. {@code closable} tells whether the month accepts a closing (T28). {@code status} and
 * {@code changes} compare both (H07.2): they are derived on every read, never stored.
 */
public record MonthClosingView(String month, LocalDate periodStart, LocalDate periodEnd, String dateBasis,
        LocalDate today, String timeZone, boolean closable, String status, List<ClosingChangeView> changes,
        ClosingSnapshotView saved, ClosingSnapshotView current) { }
