package com.malyah.accountmanager.recurrences.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecurrenceScheduleTest {
    private static final UUID CATEGORY = UUID.randomUUID(), RESPONSIBLE = UUID.randomUUID();

    @Test void reproducesTheBaseDayCalendarAndNormalizesTheEndToItsLastPeriod() {
        var schedule = RecurrenceSchedule.of(d(2027, 1, 31), d(2027, 5, 30), List.of(segment(2027, 1, config(31, RecurrenceFrequency.MONTHLY))));
        assertThat(schedule.firstDates(12)).containsExactly(d(2027, 1, 31), d(2027, 2, 28), d(2027, 3, 31), d(2027, 4, 30));
        assertThat(schedule.endMonth()).isEqualTo(YearMonth.of(2027, 4));
        assertThat(schedule.lastDueDate()).isEqualTo(d(2027, 4, 30));
        assertThat(schedule.occurrenceIn(YearMonth.of(2026, 12))).isEmpty();
        assertThat(schedule.occurrenceIn(YearMonth.of(2027, 5))).isEmpty();
        assertThat(schedule.dates(YearMonth.of(2027, 2), YearMonth.of(2027, 12), 2)).containsExactly(d(2027, 2, 28), d(2027, 3, 31));

        var unbounded = RecurrenceSchedule.of(d(2026, 9, 5), null, List.of(segment(2026, 9, config(5, RecurrenceFrequency.QUARTERLY))));
        assertThat(unbounded.lastDueDate()).isNull();
        assertThat(unbounded.endMonth()).isNull();
        assertThat(unbounded.firstDates(3)).containsExactly(d(2026, 9, 5), d(2026, 12, 5), d(2027, 3, 5));
        assertThat(unbounded.occurrenceIn(YearMonth.of(2026, 10))).isEmpty();
        assertThat(RecurrenceSchedule.of(d(2026, 9, 5), d(2026, 9, 5), List.of(segment(2026, 9, config(5, RecurrenceFrequency.MONTHLY))))
                .endMonth()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test void rejectsInconsistentSegmentsAndEnds() {
        assertThatThrownBy(() -> RecurrenceSchedule.of(d(2026, 9, 5), null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurrenceSchedule.of(d(2026, 9, 5), null, List.of(segment(2026, 10, config(5, RecurrenceFrequency.MONTHLY)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurrenceSchedule.of(d(2026, 9, 5), d(2026, 9, 4), List.of(segment(2026, 9, config(5, RecurrenceFrequency.MONTHLY)))))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThat(RecurrenceSchedule.of(d(2026, 9, 20), d(2026, 10, 10), List.of(segment(2026, 9, config(20, RecurrenceFrequency.MONTHLY))))
                .endMonth()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test void changeFromAPeriodCreatesASegmentAnchoredThereAndPropagatesOnlyEditedFieldsToLaterSegments() {
        var schedule = RecurrenceSchedule.of(d(2026, 9, 5), null, List.of(segment(2026, 9, config(5, RecurrenceFrequency.MONTHLY)),
                new RecurrenceSegment(YearMonth.of(2027, 1), new RecurrenceConfiguration("Luz nova", new BigDecimal("150.00"),
                        RecurrenceFrequency.MONTHLY, 8, CATEGORY, RESPONSIBLE), false)));
        var requested = new RecurrenceConfiguration("Energia", new BigDecimal("120.00"), RecurrenceFrequency.MONTHLY, 20, null, null);

        assertThat(schedule.editedFields(YearMonth.of(2026, 11), requested))
                .containsExactly(RecurrenceChangeField.AMOUNT, RecurrenceChangeField.DUE_DAY, RecurrenceChangeField.CATEGORY);
        var changed = schedule.withChange(YearMonth.of(2026, 11), requested);

        assertThat(changed.segments()).extracting(RecurrenceSegment::effectiveMonth)
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 11), YearMonth.of(2027, 1));
        assertThat(changed.occurrenceIn(YearMonth.of(2026, 10)).orElseThrow().dueDate()).isEqualTo(d(2026, 10, 5));
        assertThat(changed.occurrenceIn(YearMonth.of(2026, 11)).orElseThrow().dueDate()).isEqualTo(d(2026, 11, 20));
        var later = changed.segmentFor(YearMonth.of(2027, 3));
        assertThat(later.configuration()).isEqualTo(new RecurrenceConfiguration("Luz nova", new BigDecimal("120.00"),
                RecurrenceFrequency.MONTHLY, 20, null, RESPONSIBLE));
        assertThat(later.effectiveMonth()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(later.estimateReset()).isTrue();
        assertThat(changed.segmentFor(YearMonth.of(2026, 11)).estimateReset()).isTrue();
        assertThat(changed.segmentFor(YearMonth.of(2026, 9)).configuration()).isEqualTo(config(5, RecurrenceFrequency.MONTHLY));
        assertThat(schedule.editedFields(YearMonth.of(2026, 12).plusMonths(-15), requested)).isEmpty();

        var metadataOnly = schedule.withChange(YearMonth.of(2026, 11), new RecurrenceConfiguration("Energia elétrica", new BigDecimal("100.00"),
                RecurrenceFrequency.MONTHLY, 5, CATEGORY, null));
        assertThat(metadataOnly.segmentFor(YearMonth.of(2026, 11)).estimateReset()).isFalse();
        assertThat(metadataOnly.segmentFor(YearMonth.of(2027, 1)).estimateReset()).isFalse();
        assertThat(metadataOnly.segmentFor(YearMonth.of(2027, 1)).configuration().description()).isEqualTo("Energia elétrica");
        assertThat(metadataOnly.segmentFor(YearMonth.of(2027, 1)).configuration().amount()).isEqualByComparingTo("150.00");
    }

    @Test void changeAtAnExistingSegmentReplacesItAndRejectsNoOpUnscheduledOrMisalignedChanges() {
        var schedule = RecurrenceSchedule.of(d(2026, 9, 5), d(2027, 3, 5), List.of(segment(2026, 9, config(5, RecurrenceFrequency.MONTHLY)),
                segment(2026, 12, config(5, RecurrenceFrequency.MONTHLY))));
        var changed = schedule.withChange(YearMonth.of(2026, 12), config(9, RecurrenceFrequency.MONTHLY));
        assertThat(changed.segments()).hasSize(2);
        assertThat(changed.lastDueDate()).isEqualTo(d(2027, 3, 9));

        assertThatThrownBy(() -> schedule.withChange(YearMonth.of(2026, 10), config(5, RecurrenceFrequency.MONTHLY)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("Nenhum campo");
        assertThatThrownBy(() -> schedule.withChange(YearMonth.of(2027, 4), config(6, RecurrenceFrequency.MONTHLY)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("programação atual");
        assertThatThrownBy(() -> schedule.withChange(YearMonth.of(2026, 10), config(5, RecurrenceFrequency.QUARTERLY)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("fora da nova frequência");
        assertThat(schedule.withChange(YearMonth.of(2026, 9), config(5, RecurrenceFrequency.QUARTERLY)).occurrenceIn(YearMonth.of(2026, 12)))
                .isPresent();
        // The last period (March) would have no charge with a bimonthly calendar starting in December.
        assertThatThrownBy(() -> schedule.withChange(YearMonth.of(2026, 12), config(5, RecurrenceFrequency.BIMONTHLY)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("último período");
        assertThat(schedule.withChange(YearMonth.of(2027, 1), config(5, RecurrenceFrequency.BIMONTHLY)).lastDueDate())
                .isEqualTo(d(2027, 3, 5));
    }

    @Test void closureEndsAtTheLastPeriodOnOrBeforeTheCutOffAndOnlyBringsTheEndForward() {
        var schedule = RecurrenceSchedule.of(d(2026, 10, 31), d(2027, 6, 30), List.of(segment(2026, 10, config(31, RecurrenceFrequency.MONTHLY))));
        assertThat(schedule.endingAt(d(2027, 2, 28)).lastDueDate()).isEqualTo(d(2027, 2, 28));
        assertThat(schedule.endingAt(d(2027, 2, 27)).lastDueDate()).isEqualTo(d(2027, 1, 31));
        assertThat(schedule.endingAt(d(2026, 10, 31)).lastDueDate()).isEqualTo(d(2026, 10, 31));
        assertThat(schedule.endingAt(d(2027, 5, 31)).endMonth()).isEqualTo(YearMonth.of(2027, 5));
        assertThatThrownBy(() -> schedule.endingAt(d(2026, 10, 30))).isInstanceOf(RecurrenceValidationException.class)
                .hasMessageContaining("anterior ao primeiro");
        assertThatThrownBy(() -> schedule.endingAt(d(2027, 6, 30))).isInstanceOf(RecurrenceValidationException.class)
                .hasMessageContaining("só pode ser antecipado");
        assertThatThrownBy(() -> schedule.endingAt(d(2027, 9, 30))).isInstanceOf(RecurrenceValidationException.class);
        var quarterly = RecurrenceSchedule.of(d(2026, 9, 5), null, List.of(segment(2026, 9, config(5, RecurrenceFrequency.QUARTERLY))));
        assertThat(quarterly.endingAt(d(2027, 2, 1)).lastDueDate()).isEqualTo(d(2026, 12, 5));
    }

    @Test void configurationValidatesEachFieldAndListsDifferences() {
        var base = config(5, RecurrenceFrequency.MONTHLY);
        assertThat(base.differencesFrom(base)).isEmpty();
        var other = new RecurrenceConfiguration(" Água ", new BigDecimal("100.0"), RecurrenceFrequency.ANNUAL, 6, UUID.randomUUID(), UUID.randomUUID());
        assertThat(other.description()).isEqualTo("Água");
        assertThat(other.differencesFrom(base)).isEqualTo(EnumSet.of(RecurrenceChangeField.DESCRIPTION,
                RecurrenceChangeField.FREQUENCY, RecurrenceChangeField.DUE_DAY, RecurrenceChangeField.CATEGORY, RecurrenceChangeField.RESPONSIBLE));
        assertThat(base.with(other, EnumSet.of(RecurrenceChangeField.DUE_DAY))).isEqualTo(
                new RecurrenceConfiguration("Energia", new BigDecimal("100.00"), RecurrenceFrequency.MONTHLY, 6, CATEGORY, null));
        assertThat(base.with(other, EnumSet.allOf(RecurrenceChangeField.class))).isEqualTo(other);
        assertThatThrownBy(() -> new RecurrenceConfiguration(" ", BigDecimal.ONE, RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration(null, BigDecimal.ONE, RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x".repeat(201), BigDecimal.ONE, RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThat(new RecurrenceConfiguration("x".repeat(200), new BigDecimal("0.01"), RecurrenceFrequency.MONTHLY, 1, null, null)).isNotNull();
        assertThat(new RecurrenceConfiguration("x", new BigDecimal("99999999.99"), RecurrenceFrequency.MONTHLY, 31, null, null)).isNotNull();
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", new BigDecimal("0.00"), RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", new BigDecimal("100000000.00"), RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", new BigDecimal("1.001"), RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", null, RecurrenceFrequency.MONTHLY, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", BigDecimal.ONE, null, 1, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", BigDecimal.ONE, RecurrenceFrequency.MONTHLY, 0, null, null))
                .isInstanceOf(RecurrenceValidationException.class).satisfies(e ->
                        assertThat(((RecurrenceValidationException) e).field()).isEqualTo("dueDay"));
        assertThatThrownBy(() -> new RecurrenceConfiguration("x", BigDecimal.ONE, RecurrenceFrequency.MONTHLY, 32, null, null))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThat(RecurrenceChangeField.DUE_DAY.calendar()).isTrue();
        assertThat(RecurrenceChangeField.FREQUENCY.calendar()).isTrue();
        assertThat(RecurrenceChangeField.AMOUNT.calendar()).isFalse();
        assertThat(RecurrenceChangeField.RESPONSIBLE.apiName()).isEqualTo("responsibleUserId");
        assertThatThrownBy(() -> new RecurrenceSegment(null, base, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecurrenceSegment(YearMonth.of(2026, 9), null, true)).isInstanceOf(NullPointerException.class);
    }

    static RecurrenceConfiguration config(int day, RecurrenceFrequency frequency) {
        return new RecurrenceConfiguration("Energia", new BigDecimal("100.00"), frequency, day, CATEGORY, null);
    }

    static RecurrenceSegment segment(int year, int month, RecurrenceConfiguration configuration) {
        return new RecurrenceSegment(YearMonth.of(year, month), configuration, true);
    }

    static LocalDate d(int year, int month, int day) { return LocalDate.of(year, month, day); }
}
