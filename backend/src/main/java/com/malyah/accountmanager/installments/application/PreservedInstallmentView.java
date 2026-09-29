package com.malyah.accountmanager.installments.application;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * An installment the operation does not touch and why: PAID or CANCELLED (never rewritten), BEFORE_START,
 * OUTSIDE_SCOPE (after the chosen one with scope THIS), UNCHANGED (already has the new values) or NOT_SELECTED.
 */
public record PreservedInstallmentView(int number, ExpenseStatus status, String reason) { }
