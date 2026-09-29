package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** H07.2: only values and classifications make a closing outdated, never labels or the passage of time. */
class ClosingComparisonTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final UUID HOUSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    static ClosingLine pending(UUID id, String description, int day, String charge, boolean estimated,
            boolean overdue, UUID category, String categoryName) {
        return new ClosingLine(id, description, "ONE_OFF", null, null, LocalDate.of(2026, 10, day), true,
                Situation.PENDING, new BigDecimal(charge), estimated, null, overdue, category, categoryName);
    }

    static ClosingLine paid(UUID id, String description, int day, String charge, String paid) {
        return new ClosingLine(id, description, "ONE_OFF", null, null, LocalDate.of(2026, 10, day), true,
                Situation.PAID, new BigDecimal(charge), false, new BigDecimal(paid), false, null, null);
    }

    @Test
    void labelsOverdueAndScaleNeverCountAsChanges() {
        var saved = List.of(pending(A, "Aluguel", 10, "1500.00", false, false, HOUSE, "Casa e contas"));
        var current = List.of(pending(A, "Aluguel apto", 10, "1500.0", false, true, HOUSE, "Lar e contas"));
        assertThat(ClosingComparison.between(saved, current)).isEmpty();
        assertThat(ClosingSummary.of(current).digest()).isEqualTo(ClosingSummary.of(saved).digest());
        assertThat(ClosingComparison.status(true, List.of())).isEqualTo(ClosingStatus.UP_TO_DATE);
        assertThat(ClosingComparison.status(false, List.of())).isEqualTo(ClosingStatus.NOT_CLOSED);
    }

    @Test
    void everyFieldOfTheSummaryIsReportedAndTheDigestFollows() {
        var base = pending(A, "Luz", 20, "180.00", true, false, null, null);
        assertFields(base, pending(A, "Luz", 22, "180.00", true, false, null, null), ClosingField.REFERENCE_DATE);
        assertFields(base, pending(A, "Luz", 20, "175.00", true, false, null, null), ClosingField.CHARGE);
        assertFields(base, pending(A, "Luz", 20, "180.00", false, false, null, null), ClosingField.ESTIMATE);
        assertFields(base, pending(A, "Luz", 20, "180.00", true, false, HOUSE, "Casa"), ClosingField.CATEGORY);
        assertFields(base, new ClosingLine(A, "Luz", "ONE_OFF", null, null, LocalDate.of(2026, 10, 20), true,
                Situation.PAID, new BigDecimal("180.00"), true, new BigDecimal("180.00"), false, null, null),
                ClosingField.SITUATION, ClosingField.PAID_AMOUNT);
        assertFields(paid(B, "Água", 5, "150.00", "155.00"), paid(B, "Água", 5, "150.00", "150.00"),
                ClosingField.PAID_AMOUNT);
        assertFields(paid(B, "Água", 5, "150.00", "155.00"), paid(B, "Água", 5, "150.00", "155.0"));
    }

    @Test
    void entriesThatEnterOrLeaveTheMonthAreAddedOrRemovedInDateOrder() {
        var saved = List.of(pending(A, "b", 10, "10.00", false, false, null, null),
                pending(B, "a", 10, "20.00", false, false, null, null));
        var current = List.of(pending(C, "Novo", 3, "5.00", false, false, null, null),
                pending(B, "a", 10, "25.00", false, false, null, null));
        var changes = ClosingComparison.between(saved, current);
        assertThat(changes).extracting(ClosingChange::kind, ClosingChange::expenseId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(ClosingChange.Kind.ADDED, C),
                org.assertj.core.groups.Tuple.tuple(ClosingChange.Kind.CHANGED, B),
                org.assertj.core.groups.Tuple.tuple(ClosingChange.Kind.REMOVED, A));
        assertThat(changes.getFirst().saved()).isNull();
        assertThat(changes.getFirst().fields()).isEmpty();
        assertThat(changes.getLast().current()).isNull();
        assertThat(changes.get(1).fields()).containsExactly(ClosingField.CHARGE);
        assertThat(ClosingComparison.status(true, changes)).isEqualTo(ClosingStatus.OUTDATED);
        assertThat(ClosingSummary.of(current).digest()).isNotEqualTo(ClosingSummary.of(saved).digest());
    }

    @Test
    void aChangeMustMatchItsKind() {
        var line = pending(A, "x", 1, "1.00", false, false, null, null);
        assertThatThrownBy(() -> new ClosingChange(ClosingChange.Kind.ADDED, A, line, line, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClosingChange(ClosingChange.Kind.REMOVED, A, line, line, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClosingChange(ClosingChange.Kind.CHANGED, A, line, line, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClosingChange(ClosingChange.Kind.ADDED, A, null, line,
                List.of(ClosingField.CHARGE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClosingChange(ClosingChange.Kind.CHANGED, A, line, line, List.of(ClosingField.CHARGE))
                .fields()).containsExactly(ClosingField.CHARGE);
    }

    private static void assertFields(ClosingLine before, ClosingLine after, ClosingField... expected) {
        var changes = ClosingComparison.between(List.of(before), List.of(after));
        if (expected.length == 0) {
            assertThat(changes).isEmpty();
            assertThat(ClosingSummary.of(List.of(after)).digest())
                    .isEqualTo(ClosingSummary.of(List.of(before)).digest());
            return;
        }
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(ClosingChange.Kind.CHANGED);
            assertThat(change.fields()).containsExactly(expected);
        });
        assertThat(ClosingSummary.of(List.of(after)).digest()).isNotEqualTo(ClosingSummary.of(List.of(before)).digest());
    }
}
