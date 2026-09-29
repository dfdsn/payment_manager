package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/** One expense as the H06.4 CSV needs it: current data, names already resolved, no notes or attachments. */
public record ExportedExpense(UUID id, String origin, InstallmentLink installment, String description,
        String categoryName, LocalDate dueDate, BigDecimal chargeAmount, boolean chargeConfirmed, ExpenseStatus status,
        BigDecimal paidAmount, LocalDate paymentDate, String responsibleDisplayName, String payerDisplayName) { }
