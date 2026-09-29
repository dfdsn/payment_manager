package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * E06: exact sums of one group of non-cancelled expenses of a selection, split by situation, estimate and overdue
 * projection. {@code increaseTotal}/{@code discountTotal} add, per paid expense, how much the payment exceeded or
 * fell short of its charge. Sums keep the database precision, so they are not bound to a single charge limit.
 */
public record ExpenseTotalsBucket(ExpenseStatus status, boolean chargeConfirmed, boolean overdue, long count,
        BigDecimal chargeTotal, BigDecimal paidTotal, BigDecimal increaseTotal, BigDecimal discountTotal) { }
