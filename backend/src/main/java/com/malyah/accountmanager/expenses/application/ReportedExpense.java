package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * E07: one non-cancelled expense of a selection with the values and labels a month closing copies. {@code overdue}
 * is the projection at the selection's {@code today}; {@code categoryName} is the current name of the category.
 */
public record ReportedExpense(UUID id, String origin, InstallmentLink installment, String description,
        LocalDate referenceDate, boolean dueDateInformed, ExpenseStatus status, BigDecimal chargeAmount,
        boolean chargeConfirmed, BigDecimal paidAmount, boolean overdue, UUID categoryId, String categoryName) { }
