package com.malyah.accountmanager.reporting.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** T28: which months accept a closing. {@code today} is the local business date of the space. */
public final class ClosingPeriod {
    private ClosingPeriod() {
    }

    public static boolean closable(YearMonth month, LocalDate today) {
        return !Objects.requireNonNull(month, "month").isAfter(YearMonth.from(Objects.requireNonNull(today, "today")));
    }

    public static void requireClosable(YearMonth month, LocalDate today) {
        if (!closable(month, today)) throw new ClosingMonthNotAllowedException();
    }
}
