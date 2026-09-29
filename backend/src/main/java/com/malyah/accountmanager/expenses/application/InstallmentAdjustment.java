package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One effect of an installment change on a pending installment: new description, due date, category and responsible
 * (the charge amount never changes), or a logical cancellation with the given reason. The expected version guards
 * against lost updates.
 */
public record InstallmentAdjustment(UUID expenseId, long expectedVersion, boolean cancel, String cancellationReason,
        String description, LocalDate dueDate, UUID categoryId, UUID responsibleUserId, List<String> changedFields) {
    public InstallmentAdjustment {
        Objects.requireNonNull(expenseId);
        changedFields = List.copyOf(changedFields);
        if (cancel == (cancellationReason == null))
            throw new IllegalArgumentException("A cancellation requires only a reason.");
        if (!cancel && (changedFields.isEmpty() || description == null || dueDate == null))
            throw new IllegalArgumentException("An update must change a field and keep description and due date.");
    }

    public static InstallmentAdjustment cancellation(UUID expenseId, long expectedVersion, String reason) {
        return new InstallmentAdjustment(expenseId, expectedVersion, true, reason, null, null, null, null, List.of());
    }
}
