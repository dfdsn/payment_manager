package com.malyah.accountmanager.recurrences.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.malyah.accountmanager.expenses.domain.VariableEstimateReference;

/**
 * Decides what a change or closure does to each materialized occurrence (RF-REC-14 to 17, D20).
 * Paid and cancelled launches are never rewritten; confirmed variable values and confirmed due dates are preserved;
 * metadata reaches every pending launch in the scope.
 */
public final class RecurrenceChangePlanner {
    private RecurrenceChangePlanner() { }

    public enum Action { UPDATE, REMOVE, REVIEW, PRESERVE }

    public enum Reason { CHANGED, UNCHANGED, PAID, CANCELLED, AFTER_END, OUTSIDE_SCHEDULE }

    public record OccurrenceState(UUID expenseId, LocalDate scheduledDueDate, long version, String status,
            boolean chargeConfirmed, BigDecimal amount, LocalDate dueDate, String description, UUID categoryId,
            UUID responsibleUserId, boolean dueDateCorrected) {
        public OccurrenceState {
            Objects.requireNonNull(expenseId);
            Objects.requireNonNull(scheduledDueDate);
            Objects.requireNonNull(status);
        }
        public YearMonth month() { return YearMonth.from(scheduledDueDate); }
        boolean pending() { return "PENDING".equals(status); }
    }

    public record Effect(OccurrenceState occurrence, Action action, Reason reason, String description,
            BigDecimal amount, LocalDate dueDate, UUID categoryId, UUID responsibleUserId,
            List<RecurrenceChangeField> changedFields, List<RecurrenceChangeField> preservedFields) { }

    public static List<Effect> planChange(RecurrenceSchedule after, YearMonth from, Set<RecurrenceChangeField> edited,
            boolean variable, Collection<OccurrenceState> materialized) {
        var confirmed = materialized.stream().filter(OccurrenceState::chargeConfirmed)
                .map(o -> new VariableEstimateReference.ConfirmedCharge(o.scheduledDueDate(), o.amount())).toList();
        var bases = after.segments().stream().filter(RecurrenceSegment::estimateReset)
                .map(s -> new VariableEstimateReference.EstimateBase(s.effectiveMonth(), s.configuration().amount()))
                .toList();
        var result = new ArrayList<Effect>();
        for (var occurrence : sorted(materialized)) {
            // Periods before the change keep their values; periods after the end already left the program.
            if (occurrence.month().isBefore(from) || after.endMonth() != null && occurrence.month().isAfter(after.endMonth()))
                continue;
            var scheduled = after.occurrenceIn(occurrence.month());
            if ("CANCELLED".equals(occurrence.status())) { result.add(keep(occurrence, Action.PRESERVE, Reason.CANCELLED)); continue; }
            if (scheduled.isEmpty()) { result.add(outOfProgram(occurrence, Reason.OUTSIDE_SCHEDULE)); continue; }
            if (!occurrence.pending()) { result.add(keep(occurrence, Action.PRESERVE, Reason.PAID)); continue; }
            var configuration = scheduled.get().segment().configuration();
            var changed = new ArrayList<RecurrenceChangeField>();
            var preserved = new ArrayList<RecurrenceChangeField>();
            var description = occurrence.description();
            if (edited.contains(RecurrenceChangeField.DESCRIPTION) && !configuration.description().equals(description)) {
                description = configuration.description(); changed.add(RecurrenceChangeField.DESCRIPTION);
            }
            var category = occurrence.categoryId();
            if (edited.contains(RecurrenceChangeField.CATEGORY) && !Objects.equals(configuration.categoryId(), category)) {
                category = configuration.categoryId(); changed.add(RecurrenceChangeField.CATEGORY);
            }
            var responsible = occurrence.responsibleUserId();
            if (edited.contains(RecurrenceChangeField.RESPONSIBLE)
                    && !Objects.equals(configuration.responsibleUserId(), responsible)) {
                responsible = configuration.responsibleUserId(); changed.add(RecurrenceChangeField.RESPONSIBLE);
            }
            var amount = occurrence.amount();
            if (edited.contains(RecurrenceChangeField.AMOUNT)) {
                var target = !variable ? configuration.amount() : occurrence.chargeConfirmed() ? null
                        : VariableEstimateReference.estimateFor(bases, confirmed, occurrence.scheduledDueDate());
                if (target == null) preserved.add(RecurrenceChangeField.AMOUNT);
                else if (target.compareTo(amount) != 0) { amount = target; changed.add(RecurrenceChangeField.AMOUNT); }
            }
            var dueDate = occurrence.dueDate();
            if ((edited.contains(RecurrenceChangeField.DUE_DAY) || edited.contains(RecurrenceChangeField.FREQUENCY))
                    && !scheduled.get().dueDate().equals(dueDate)) {
                if (occurrence.dueDateCorrected() || variable && occurrence.chargeConfirmed())
                    preserved.add(RecurrenceChangeField.DUE_DAY);
                else { dueDate = scheduled.get().dueDate(); changed.add(RecurrenceChangeField.DUE_DAY); }
            }
            result.add(new Effect(occurrence, changed.isEmpty() ? Action.PRESERVE : Action.UPDATE,
                    changed.isEmpty() ? Reason.UNCHANGED : Reason.CHANGED, description, amount, dueDate, category,
                    responsible, List.copyOf(changed), List.copyOf(preserved)));
        }
        return List.copyOf(result);
    }

    /** RF-REC-16/17: only periods after the last one are affected; earlier pending launches are preserved. */
    public static List<Effect> planClosure(YearMonth endMonth, Collection<OccurrenceState> materialized) {
        Objects.requireNonNull(endMonth);
        var result = new ArrayList<Effect>();
        for (var occurrence : sorted(materialized)) {
            if (!occurrence.month().isAfter(endMonth)) continue;
            result.add("CANCELLED".equals(occurrence.status()) ? keep(occurrence, Action.PRESERVE, Reason.CANCELLED)
                    : outOfProgram(occurrence, Reason.AFTER_END));
        }
        return List.copyOf(result);
    }

    /**
     * Estimated-only pending launches leave the active program; paid ones, confirmed values and individually corrected
     * due dates stay for individual review.
     */
    private static Effect outOfProgram(OccurrenceState occurrence, Reason reason) {
        var removable = occurrence.pending() && !occurrence.chargeConfirmed() && !occurrence.dueDateCorrected();
        return keep(occurrence, removable ? Action.REMOVE : Action.REVIEW, reason);
    }

    private static Effect keep(OccurrenceState o, Action action, Reason reason) {
        return new Effect(o, action, reason, o.description(), o.amount(), o.dueDate(), o.categoryId(),
                o.responsibleUserId(), List.of(), List.of());
    }

    private static List<OccurrenceState> sorted(Collection<OccurrenceState> materialized) {
        return materialized.stream().sorted(Comparator.comparing(OccurrenceState::scheduledDueDate)).toList();
    }
}
