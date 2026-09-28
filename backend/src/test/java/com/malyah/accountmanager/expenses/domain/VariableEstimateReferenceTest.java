package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.domain.VariableEstimateReference.ConfirmedCharge;

class VariableEstimateReferenceTest {
    private static final BigDecimal INITIAL = new BigDecimal("180.00");

    @Test
    void usesInitialEstimateUntilAConfirmationScheduledBeforeTheOccurrenceExists() {
        assertThat(VariableEstimateReference.estimateFor(INITIAL, List.of(), LocalDate.of(2026, 10, 5)))
                .isEqualByComparingTo("180.00");
        var sameDay = List.of(new ConfirmedCharge(LocalDate.of(2026, 10, 5), new BigDecimal("195.00")));
        assertThat(VariableEstimateReference.estimateFor(INITIAL, sameDay, LocalDate.of(2026, 10, 5)))
                .isEqualByComparingTo("180.00");
    }

    @Test
    void followsDueOrderSoAnOlderCorrectionDoesNotReplaceTheLatestReference() {
        // PRD example: September confirmed at R$ 195 and January corrected later.
        var charges = List.of(
                new ConfirmedCharge(LocalDate.of(2026, 9, 5), new BigDecimal("195.00")),
                new ConfirmedCharge(LocalDate.of(2026, 1, 5), new BigDecimal("150.00")),
                new ConfirmedCharge(LocalDate.of(2026, 12, 5), new BigDecimal("210.00")));
        assertThat(VariableEstimateReference.estimateFor(INITIAL, charges, LocalDate.of(2026, 10, 5)))
                .isEqualByComparingTo("195.00");
        assertThat(VariableEstimateReference.estimateFor(INITIAL, charges, LocalDate.of(2026, 3, 5)))
                .isEqualByComparingTo("150.00");
        assertThat(VariableEstimateReference.estimateFor(INITIAL, charges, LocalDate.of(2027, 1, 5)))
                .isEqualByComparingTo("210.00");
        assertThat(VariableEstimateReference.estimateFor(INITIAL, charges, LocalDate.of(2025, 12, 5)))
                .isEqualByComparingTo("180.00");
    }

    @Test
    void rejectsMissingInputs() {
        assertThatThrownBy(() -> VariableEstimateReference.estimateFor((java.math.BigDecimal) null, List.of(), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> VariableEstimateReference.estimateFor(INITIAL, List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfirmedCharge(null, INITIAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfirmedCharge(LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anExplicitEstimateResetIgnoresConfirmationsOfEarlierPeriods() {
        var bases = List.of(new VariableEstimateReference.EstimateBase(java.time.YearMonth.of(2026, 9), INITIAL),
                new VariableEstimateReference.EstimateBase(java.time.YearMonth.of(2026, 12), new BigDecimal("250.00")));
        var charges = List.of(new ConfirmedCharge(LocalDate.of(2026, 10, 5), new BigDecimal("195.00")),
                new ConfirmedCharge(LocalDate.of(2027, 1, 5), new BigDecimal("260.00")));
        assertThat(VariableEstimateReference.estimateFor(bases, charges, LocalDate.of(2026, 11, 5))).isEqualByComparingTo("195.00");
        assertThat(VariableEstimateReference.estimateFor(bases, charges, LocalDate.of(2026, 12, 5))).isEqualByComparingTo("250.00");
        assertThat(VariableEstimateReference.estimateFor(bases, charges, LocalDate.of(2027, 1, 5))).isEqualByComparingTo("250.00");
        assertThat(VariableEstimateReference.estimateFor(bases, charges, LocalDate.of(2027, 2, 5))).isEqualByComparingTo("260.00");
        assertThat(VariableEstimateReference.estimateFor(List.of(bases.get(1), bases.get(0)), List.of(), LocalDate.of(2027, 2, 5)))
                .isEqualByComparingTo("250.00");
        assertThat(VariableEstimateReference.estimateFor(bases, List.of(new ConfirmedCharge(LocalDate.of(2026, 12, 1),
                new BigDecimal("240.00"))), LocalDate.of(2026, 12, 20))).isEqualByComparingTo("240.00");
        assertThatThrownBy(() -> VariableEstimateReference.estimateFor(bases, charges, LocalDate.of(2026, 8, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VariableEstimateReference.estimateFor(bases, charges, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VariableEstimateReference.EstimateBase(null, INITIAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VariableEstimateReference.EstimateBase(java.time.YearMonth.of(2026, 1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
