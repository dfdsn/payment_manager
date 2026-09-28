package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

public record InstallmentExpenseSnapshot(UUID expenseId, int number, int count, BigDecimal amount, LocalDate dueDate,
        ExpenseStatus status, long version) { }
