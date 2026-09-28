package com.malyah.accountmanager.recurrences.domain;

public enum RecurrenceFrequency {
    MONTHLY(1), BIMONTHLY(2), QUARTERLY(3), SEMIANNUAL(6), ANNUAL(12);

    private final int months;
    RecurrenceFrequency(int months) { this.months = months; }
    public int months() { return months; }
}
