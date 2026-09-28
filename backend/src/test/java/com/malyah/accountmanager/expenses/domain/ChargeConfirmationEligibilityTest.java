package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ChargeConfirmationEligibilityTest {
    @Test
    void onlyAPendingEstimateAtTheExpectedVersionCanBeConfirmed() {
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.PENDING, 3, 3))
                .isEqualTo(ChargeConfirmationEligibility.ELIGIBLE);
        assertThat(ChargeConfirmationEligibility.evaluate(true, ExpenseStatus.PENDING, 3, 3))
                .isEqualTo(ChargeConfirmationEligibility.ALREADY_CONFIRMED);
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.PAID, 3, 3))
                .isEqualTo(ChargeConfirmationEligibility.STATE_INCOMPATIBLE);
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.CANCELLED, 3, 3))
                .isEqualTo(ChargeConfirmationEligibility.STATE_INCOMPATIBLE);
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.PENDING, 4, 3))
                .isEqualTo(ChargeConfirmationEligibility.VERSION_CONFLICT);
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.PENDING, 0, -1))
                .isEqualTo(ChargeConfirmationEligibility.VERSION_CONFLICT);
        assertThat(ChargeConfirmationEligibility.evaluate(false, ExpenseStatus.PENDING, 0, 0))
                .isEqualTo(ChargeConfirmationEligibility.ELIGIBLE);
    }

    @Test
    void genericCorrectionCannotReplaceAnEstimateButMayCorrectAConfirmedCharge() {
        assertThat(ChargeConfirmationEligibility.correctionKeepsEstimate(
                false, new BigDecimal("180.00"), new BigDecimal("180.0"))).isTrue();
        assertThat(ChargeConfirmationEligibility.correctionKeepsEstimate(
                false, new BigDecimal("180.00"), new BigDecimal("195.00"))).isFalse();
        assertThat(ChargeConfirmationEligibility.correctionKeepsEstimate(
                true, new BigDecimal("180.00"), new BigDecimal("195.00"))).isTrue();
    }
}
