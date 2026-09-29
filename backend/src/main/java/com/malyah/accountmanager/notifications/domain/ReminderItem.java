package com.malyah.accountmanager.notifications.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One bill a summary may mention: a pending expense ({@code expenseId}), or a recurrence forecast not yet
 * materialized ({@code recurrenceId} + {@code scheduledDueDate}). The key is the stable identity used to never
 * count the same bill twice.
 */
public record ReminderItem(UUID expenseId, UUID recurrenceId, LocalDate scheduledDueDate, String description,
        BigDecimal amount, LocalDate dueDate, boolean estimated, String origin, Integer installmentNumber,
        Integer installmentCount) {
    public static final String FORECAST_ORIGIN = "RECURRENCE_FORECAST";

    public ReminderItem {
        Objects.requireNonNull(description);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(dueDate);
        Objects.requireNonNull(origin);
        var isExpense = expenseId != null && recurrenceId == null && scheduledDueDate == null;
        var isForecast = expenseId == null && recurrenceId != null && scheduledDueDate != null;
        if (!isExpense && !isForecast)
            throw new IllegalArgumentException("An item is either an expense or a forecast.");
    }

    public static ReminderItem expense(UUID id, String origin, String description, BigDecimal amount,
            LocalDate dueDate, boolean estimated, Integer installmentNumber, Integer installmentCount) {
        return new ReminderItem(id, null, null, description, amount, dueDate, estimated, origin, installmentNumber,
                installmentCount);
    }

    public static ReminderItem forecast(UUID recurrenceId, LocalDate scheduledDueDate, String description,
            BigDecimal amount, boolean estimated) {
        return new ReminderItem(null, recurrenceId, scheduledDueDate, description, amount, scheduledDueDate,
                estimated, FORECAST_ORIGIN, null, null);
    }

    public String key() {
        return expenseId != null ? "E:" + expenseId : "F:" + recurrenceId + ":" + scheduledDueDate;
    }

    public boolean forecast() {
        return expenseId == null;
    }

    public boolean overdue(LocalDate today) {
        return dueDate.isBefore(today);
    }

    /** Description with the parcel n/N, as the lists show it. */
    public String label() {
        return installmentNumber == null ? description
                : description + " (" + installmentNumber + "/" + installmentCount + ")";
    }
}
