package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One effect of a recurrence change or closure on a pending launch: new values for the listed fields, or removal from
 * the active program (logical cancellation with the given reason). The expected version guards against lost updates.
 */
public record RecurringOccurrenceAdjustment(UUID expenseId, long expectedVersion, boolean remove, String removalReason,
        String description, BigDecimal amount, LocalDate dueDate, UUID categoryId, UUID responsibleUserId,
        List<String> changedFields) {
    public RecurringOccurrenceAdjustment {
        Objects.requireNonNull(expenseId);
        changedFields = List.copyOf(changedFields);
        if (remove == (removalReason == null)) throw new IllegalArgumentException("Removal requires only a reason.");
        if (!remove && changedFields.isEmpty()) throw new IllegalArgumentException("An update must change a field.");
    }
}
