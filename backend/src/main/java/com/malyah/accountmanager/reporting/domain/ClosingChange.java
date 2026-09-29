package com.malyah.accountmanager.reporting.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One difference between the saved version and the current data of a month: an entry that entered the month
 * ({@code saved} null), left it ({@code current} null: cancelled or moved to another month), or changed a field that
 * decides values or classifications.
 */
public record ClosingChange(Kind kind, UUID expenseId, ClosingLine saved, ClosingLine current,
        List<ClosingField> fields) {
    public enum Kind { ADDED, REMOVED, CHANGED }

    public ClosingChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expenseId, "expenseId");
        fields = List.copyOf(fields);
        if ((saved == null) != (kind == Kind.ADDED) || (current == null) != (kind == Kind.REMOVED))
            throw new IllegalArgumentException("A diferença não corresponde ao seu tipo.");
        if ((kind == Kind.CHANGED) == fields.isEmpty())
            throw new IllegalArgumentException("Somente uma alteração lista campos.");
    }
}
