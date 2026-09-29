package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * One installment entry as the installments module sees it. Description, category and responsible are read per entry
 * because a change applied to "this and the following pending installments" can make them differ from the purchase.
 */
public record InstallmentExpenseSnapshot(UUID expenseId, UUID purchaseId, int number, int count, BigDecimal amount,
        LocalDate dueDate, ExpenseStatus status, long version, String description, UUID categoryId,
        String categoryName, UUID responsibleUserId, String responsibleDisplayName, LocalDate paymentDate,
        BigDecimal paidAmount) { }
