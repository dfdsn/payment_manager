package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;

/** H06.3: how many selected expenses have {@code date} as their reference date. */
public record ExpenseDayCount(LocalDate date, long count) { }
