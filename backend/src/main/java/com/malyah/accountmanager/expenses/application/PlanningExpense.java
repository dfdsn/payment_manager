package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * H06.3: one materialized expense of the planning, as it stands now. {@code referenceDate} places it in the
 * horizon (the due date, or the payment date of a paid expense without due date).
 */
public record PlanningExpense(UUID id, String origin, InstallmentLink installment, String description,
        LocalDate referenceDate, LocalDate dueDate, BigDecimal chargeAmount, boolean chargeConfirmed,
        ExpenseStatus status, BigDecimal paidAmount, LocalDate paymentDate, String categoryName,
        String responsibleDisplayName) { }
