package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

/** H08.3: the situation of an expense now (PENDING, PAID or CANCELLED) next to a reminder that mentioned it. */
public record ReminderExpenseState(UUID id, String status, LocalDate dueDate) { }
