package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * H08.2: a pending expense as a reminder shows it. {@code chargeConfirmed} is false only for a recurrence
 * occurrence whose variable value is still an estimate; {@code installment} identifies a parcel n/N.
 */
public record ReminderExpense(UUID id, String origin, InstallmentLink installment, String description,
        LocalDate dueDate, BigDecimal chargeAmount, boolean chargeConfirmed) { }
