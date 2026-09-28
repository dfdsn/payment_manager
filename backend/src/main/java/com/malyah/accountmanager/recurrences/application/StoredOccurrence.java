package com.malyah.accountmanager.recurrences.application;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.UUID;
public record StoredOccurrence(UUID recurrenceId, LocalDate scheduledDueDate, UUID expenseId,
        LocalDate actualDueDate, String status, BigDecimal amount, boolean chargeConfirmed, String reviewReason) {
    public StoredOccurrence(UUID recurrenceId, LocalDate scheduledDueDate, UUID expenseId, LocalDate actualDueDate,
            String status, BigDecimal amount, boolean chargeConfirmed) {
        this(recurrenceId, scheduledDueDate, expenseId, actualDueDate, status, amount, chargeConfirmed, null);
    }
}
