package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * H06.3: sums of the non-cancelled expenses of one month (by reference date) sharing origin, situation and charge
 * confirmation. {@code paidTotal} is zero for pending expenses.
 */
public record PlanningExpenseBucket(YearMonth month, String origin, ExpenseStatus status, boolean chargeConfirmed,
        long count, BigDecimal chargeTotal, BigDecimal paidTotal) { }
