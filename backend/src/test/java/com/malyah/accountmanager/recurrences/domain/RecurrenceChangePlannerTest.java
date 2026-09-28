package com.malyah.accountmanager.recurrences.domain;

import static com.malyah.accountmanager.recurrences.domain.RecurrenceScheduleTest.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.Action;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.Effect;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.OccurrenceState;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.Reason;

class RecurrenceChangePlannerTest {
    private static final UUID CATEGORY = UUID.randomUUID(), NEW_CATEGORY = UUID.randomUUID(), RESPONSIBLE = UUID.randomUUID();
    private static final RecurrenceConfiguration BASE =
            new RecurrenceConfiguration("Energia", new BigDecimal("180.00"), RecurrenceFrequency.MONTHLY, 5, CATEGORY, null);
    private static final RecurrenceSchedule SCHEDULE =
            RecurrenceSchedule.of(d(2026, 9, 5), null, List.of(new RecurrenceSegment(YearMonth.of(2026, 9), BASE, true)));

    @Test void metadataReachesEveryPendingLaunchIncludingConfirmedOnesButNeverPaidCancelledOrEarlierPeriods() {
        var requested = new RecurrenceConfiguration("Energia elétrica", new BigDecimal("180.00"), RecurrenceFrequency.MONTHLY, 5,
                NEW_CATEGORY, RESPONSIBLE);
        var after = SCHEDULE.withChange(YearMonth.of(2026, 10), requested);
        var edited = EnumSet.of(RecurrenceChangeField.DESCRIPTION, RecurrenceChangeField.CATEGORY, RecurrenceChangeField.RESPONSIBLE);
        var sep = pending(d(2026, 9, 5), false, "180.00");
        var oct = pending(d(2026, 10, 5), true, "195.00");
        var nov = state(d(2026, 11, 5), "PAID", true, "200.00", d(2026, 11, 5), false);
        var dec = state(d(2026, 12, 5), "CANCELLED", false, "195.00", d(2026, 12, 5), false);
        var jan = pending(d(2027, 1, 5), false, "195.00");

        var effects = RecurrenceChangePlanner.planChange(after, YearMonth.of(2026, 10), edited, true, List.of(jan, dec, nov, oct, sep));

        assertThat(effects).extracting(e -> e.occurrence().scheduledDueDate()).containsExactly(d(2026, 10, 5), d(2026, 11, 5),
                d(2026, 12, 5), d(2027, 1, 5));
        assertThat(effects.get(0)).satisfies(e -> {
            assertThat(e.action()).isEqualTo(Action.UPDATE); assertThat(e.reason()).isEqualTo(Reason.CHANGED);
            assertThat(e.description()).isEqualTo("Energia elétrica"); assertThat(e.categoryId()).isEqualTo(NEW_CATEGORY);
            assertThat(e.responsibleUserId()).isEqualTo(RESPONSIBLE); assertThat(e.amount()).isEqualByComparingTo("195.00");
            assertThat(e.changedFields()).containsExactly(RecurrenceChangeField.DESCRIPTION, RecurrenceChangeField.CATEGORY,
                    RecurrenceChangeField.RESPONSIBLE);
            assertThat(e.preservedFields()).isEmpty();});
        assertThat(effects.get(1)).satisfies(e -> { assertThat(e.action()).isEqualTo(Action.PRESERVE); assertThat(e.reason()).isEqualTo(Reason.PAID);
            assertThat(e.description()).isEqualTo("Energia"); assertThat(e.changedFields()).isEmpty();});
        assertThat(effects.get(2)).satisfies(e -> { assertThat(e.action()).isEqualTo(Action.PRESERVE); assertThat(e.reason()).isEqualTo(Reason.CANCELLED);});
        assertThat(effects.get(3).action()).isEqualTo(Action.UPDATE);
    }

    @Test void valueAndCalendarChangesPreserveConfirmedValuesAndConfirmedDueDates() {
        var requested = new RecurrenceConfiguration("Energia", new BigDecimal("250.00"), RecurrenceFrequency.MONTHLY, 10, CATEGORY, null);
        var after = SCHEDULE.withChange(YearMonth.of(2026, 10), requested);
        var edited = EnumSet.of(RecurrenceChangeField.AMOUNT, RecurrenceChangeField.DUE_DAY);
        var confirmed = pending(d(2026, 10, 5), true, "195.00");
        var corrected = state(d(2026, 11, 5), "PENDING", false, "180.00", d(2026, 11, 15), true);
        var estimate = pending(d(2026, 12, 5), false, "180.00");
        var alreadyThere = state(d(2027, 1, 5), "PENDING", false, "195.00", d(2027, 1, 10), false);

        var variable = RecurrenceChangePlanner.planChange(after, YearMonth.of(2026, 10), edited, true,
                List.of(confirmed, corrected, estimate, alreadyThere));

        assertThat(variable.get(0)).satisfies(e -> { assertThat(e.action()).isEqualTo(Action.PRESERVE);
            assertThat(e.reason()).isEqualTo(Reason.UNCHANGED); assertThat(e.amount()).isEqualByComparingTo("195.00");
            assertThat(e.dueDate()).isEqualTo(d(2026, 10, 5));
            assertThat(e.preservedFields()).containsExactly(RecurrenceChangeField.AMOUNT, RecurrenceChangeField.DUE_DAY);});
        // The reset estimate (250) applies from October on; the October confirmation (195) is the latest reference.
        assertThat(variable.get(1)).satisfies(e -> { assertThat(e.action()).isEqualTo(Action.UPDATE);
            assertThat(e.amount()).isEqualByComparingTo("195.00"); assertThat(e.dueDate()).isEqualTo(d(2026, 11, 15));
            assertThat(e.changedFields()).containsExactly(RecurrenceChangeField.AMOUNT);
            assertThat(e.preservedFields()).containsExactly(RecurrenceChangeField.DUE_DAY);});
        assertThat(variable.get(2)).satisfies(e -> { assertThat(e.amount()).isEqualByComparingTo("195.00");
            assertThat(e.dueDate()).isEqualTo(d(2026, 12, 10));
            assertThat(e.changedFields()).containsExactly(RecurrenceChangeField.AMOUNT, RecurrenceChangeField.DUE_DAY);});
        assertThat(variable.get(3)).satisfies(e -> { assertThat(e.action()).isEqualTo(Action.PRESERVE);
            assertThat(e.changedFields()).isEmpty(); assertThat(e.preservedFields()).isEmpty();});

        var withoutConfirmation = RecurrenceChangePlanner.planChange(after, YearMonth.of(2026, 10), edited, true, List.of(estimate));
        assertThat(withoutConfirmation.getFirst().amount()).isEqualByComparingTo("250.00");

        var fixed = RecurrenceChangePlanner.planChange(after, YearMonth.of(2026, 10), edited, false,
                List.of(state(d(2026, 10, 5), "PENDING", false, "180.00", d(2026, 10, 5), false), corrected));
        assertThat(fixed.get(0)).satisfies(e -> { assertThat(e.amount()).isEqualByComparingTo("250.00");
            assertThat(e.dueDate()).isEqualTo(d(2026, 10, 10));});
        assertThat(fixed.get(1)).satisfies(e -> { assertThat(e.amount()).isEqualByComparingTo("250.00");
            assertThat(e.dueDate()).isEqualTo(d(2026, 11, 15));});
    }

    @Test void periodsLeavingTheCalendarAreRemovedOnlyWhenEstimatedAndOtherwiseGoToReview() {
        var after = SCHEDULE.withChange(YearMonth.of(2026, 10),
                new RecurrenceConfiguration("Energia", new BigDecimal("180.00"), RecurrenceFrequency.BIMONTHLY, 5, CATEGORY, null));
        var edited = EnumSet.of(RecurrenceChangeField.FREQUENCY);
        var effects = RecurrenceChangePlanner.planChange(after, YearMonth.of(2026, 10), edited, true, List.of(
                pending(d(2026, 11, 5), false, "180.00"), pending(d(2027, 1, 5), true, "190.00"),
                state(d(2027, 3, 5), "PAID", true, "190.00", d(2027, 3, 5), false),
                state(d(2027, 5, 5), "CANCELLED", false, "190.00", d(2027, 5, 5), false),
                pending(d(2026, 12, 5), false, "180.00")));
        assertThat(effects).extracting(Effect::action, Effect::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Action.REMOVE, Reason.OUTSIDE_SCHEDULE),
                org.assertj.core.groups.Tuple.tuple(Action.PRESERVE, Reason.UNCHANGED),
                org.assertj.core.groups.Tuple.tuple(Action.REVIEW, Reason.OUTSIDE_SCHEDULE),
                org.assertj.core.groups.Tuple.tuple(Action.REVIEW, Reason.OUTSIDE_SCHEDULE),
                org.assertj.core.groups.Tuple.tuple(Action.PRESERVE, Reason.CANCELLED));

        var ended = RecurrenceSchedule.of(d(2026, 9, 5), d(2026, 11, 5), List.of(new RecurrenceSegment(YearMonth.of(2026, 9), BASE, true)));
        var changed = ended.withChange(YearMonth.of(2026, 10),
                new RecurrenceConfiguration("Luz", new BigDecimal("180.00"), RecurrenceFrequency.MONTHLY, 5, CATEGORY, null));
        assertThat(RecurrenceChangePlanner.planChange(changed, YearMonth.of(2026, 10), EnumSet.of(RecurrenceChangeField.DESCRIPTION),
                true, List.of(state(d(2026, 12, 5), "PAID", true, "1.00", d(2026, 12, 5), false)))).isEmpty();
    }

    @Test void closureAffectsOnlyPeriodsAfterTheEnd() {
        var effects = RecurrenceChangePlanner.planClosure(YearMonth.of(2026, 10), List.of(
                pending(d(2026, 10, 5), false, "1.00"), pending(d(2026, 11, 5), false, "1.00"),
                pending(d(2026, 12, 5), true, "1.00"), state(d(2027, 1, 5), "PAID", true, "1.00", d(2027, 1, 5), false),
                state(d(2027, 2, 5), "CANCELLED", false, "1.00", d(2027, 2, 5), false),
                state(d(2027, 3, 5), "PENDING", false, "1.00", d(2027, 3, 12), true)));
        assertThat(effects).extracting(Effect::action)
                .containsExactly(Action.REMOVE, Action.REVIEW, Action.REVIEW, Action.PRESERVE, Action.REVIEW);
        assertThat(effects).extracting(Effect::reason).containsExactly(Reason.AFTER_END, Reason.AFTER_END, Reason.AFTER_END,
                Reason.CANCELLED, Reason.AFTER_END);
        assertThat(effects.getFirst().description()).isEqualTo("Energia");
        assertThatThrownBy(() -> RecurrenceChangePlanner.planClosure(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pending(null, false, "1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OccurrenceState(null, d(2026, 1, 1), 0, "PENDING", false, BigDecimal.ONE, null, "x", null, null, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OccurrenceState(UUID.randomUUID(), d(2026, 1, 1), 0, null, false, BigDecimal.ONE, null, "x", null, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    private static OccurrenceState pending(LocalDate scheduled, boolean confirmed, String amount) {
        return state(scheduled, "PENDING", confirmed, amount, scheduled, false);
    }

    private static OccurrenceState state(LocalDate scheduled, String status, boolean confirmed, String amount, LocalDate due,
            boolean dueDateCorrected) {
        return new OccurrenceState(UUID.randomUUID(), scheduled, 0, status, confirmed, new BigDecimal(amount), due, "Energia",
                CATEGORY, null, dueDateCorrected);
    }
}
