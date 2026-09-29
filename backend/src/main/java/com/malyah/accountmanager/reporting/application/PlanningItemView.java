package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.InstallmentLink;

/**
 * One planned value. {@code kind} is {@code EXPENSE} (a real expense, whose current data prevail) or
 * {@code FORECAST} (a recurrence occurrence not generated yet). {@code date} places it in the month: the reference
 * date of an expense or the projected due date of a forecast. {@code status} is {@code PENDING}, {@code PAID} or
 * {@code FORECAST}; {@code estimated} marks a value still to be confirmed.
 */
public record PlanningItemView(String kind, UUID expenseId, UUID recurrenceId, String origin,
        InstallmentLink installment, String description, LocalDate date, LocalDate dueDate, String amount,
        boolean estimated, String status, boolean overdue, String paidAmount, LocalDate paymentDate,
        String categoryName, String responsibleDisplayName) { }
