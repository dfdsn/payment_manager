package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** BRL amounts of reports: always two decimal places, never rounded away from the exact database sum. */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() {
    }

    public static BigDecimal of(BigDecimal value) {
        // Sums of NUMERIC(10,2) already carry two places; UNNECESSARY fails instead of hiding a lost cent.
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
