package com.malyah.accountmanager.reporting.application;

/** Result of closing a month; {@code replayed} marks the answer to a repeated request with the same key. */
public record CloseMonthResult(MonthClosingView closing, boolean replayed) { }
