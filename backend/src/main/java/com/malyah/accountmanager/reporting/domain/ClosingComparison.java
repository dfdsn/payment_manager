package com.malyah.accountmanager.reporting.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * RF-FEC-03 and RF-FEC-05: compares the lines of a saved version with the current lines of the same month, both
 * computed with the same rules. Only the fields of {@link ClosingField} count, exactly the ones that form the content
 * digest, so labels (description, category name) and the overdue projection never make a closing outdated.
 */
public final class ClosingComparison {
    private static final Comparator<ClosingChange> ORDER = Comparator
            .comparing((ClosingChange change) -> line(change).referenceDate())
            .thenComparing(change -> line(change).description().toLowerCase(Locale.ROOT))
            .thenComparing(ClosingChange::expenseId);

    private ClosingComparison() {
    }

    public static List<ClosingChange> between(List<ClosingLine> saved, List<ClosingLine> current) {
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(current, "current");
        var remaining = new LinkedHashMap<java.util.UUID, ClosingLine>();
        current.forEach(line -> remaining.put(line.expenseId(), line));
        var changes = new ArrayList<ClosingChange>();
        for (var before : saved) {
            var after = remaining.remove(before.expenseId());
            if (after == null) {
                changes.add(new ClosingChange(ClosingChange.Kind.REMOVED, before.expenseId(), before, null,
                        List.of()));
                continue;
            }
            var fields = fields(before, after);
            if (!fields.isEmpty())
                changes.add(new ClosingChange(ClosingChange.Kind.CHANGED, before.expenseId(), before, after, fields));
        }
        remaining.values().forEach(after -> changes.add(new ClosingChange(ClosingChange.Kind.ADDED,
                after.expenseId(), null, after, List.of())));
        changes.sort(ORDER);
        return List.copyOf(changes);
    }

    public static ClosingStatus status(boolean closed, List<ClosingChange> changes) {
        if (!closed) return ClosingStatus.NOT_CLOSED;
        return changes.isEmpty() ? ClosingStatus.UP_TO_DATE : ClosingStatus.OUTDATED;
    }

    static List<ClosingField> fields(ClosingLine before, ClosingLine after) {
        var fields = new ArrayList<ClosingField>();
        if (!before.referenceDate().equals(after.referenceDate())) fields.add(ClosingField.REFERENCE_DATE);
        if (before.situation() != after.situation()) fields.add(ClosingField.SITUATION);
        if (before.chargeAmount().compareTo(after.chargeAmount()) != 0) fields.add(ClosingField.CHARGE);
        if (before.estimated() != after.estimated()) fields.add(ClosingField.ESTIMATE);
        if (!samePaid(before, after)) fields.add(ClosingField.PAID_AMOUNT);
        if (!Objects.equals(before.categoryId(), after.categoryId())) fields.add(ClosingField.CATEGORY);
        return fields;
    }

    private static boolean samePaid(ClosingLine before, ClosingLine after) {
        if (before.paidAmount() == null || after.paidAmount() == null)
            return before.paidAmount() == after.paidAmount();
        return before.paidAmount().compareTo(after.paidAmount()) == 0;
    }

    private static ClosingLine line(ClosingChange change) {
        return change.current() == null ? change.saved() : change.current();
    }
}
