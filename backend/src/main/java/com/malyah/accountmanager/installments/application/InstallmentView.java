package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/** One installment n/N; the expense fields are empty in a preview. */
public record InstallmentView(int number, int count, String amount, LocalDate dueDate, UUID expenseId,
        ExpenseStatus status) { }
