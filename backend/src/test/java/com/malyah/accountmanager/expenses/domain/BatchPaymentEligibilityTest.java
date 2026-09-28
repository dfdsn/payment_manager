package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchPaymentEligibilityTest {
    @Test void identifiesEveryBatchEligibilityOutcome() {
        assertThat(BatchPaymentEligibility.evaluate(true, ExpenseStatus.PENDING, 2, 2))
                .isEqualTo(BatchPaymentEligibility.ELIGIBLE);
        assertThat(BatchPaymentEligibility.evaluate(false, ExpenseStatus.PENDING, 2, 2))
                .isEqualTo(BatchPaymentEligibility.AMOUNT_UNCONFIRMED);
        assertThat(BatchPaymentEligibility.evaluate(true, ExpenseStatus.PAID, 2, 2))
                .isEqualTo(BatchPaymentEligibility.STATE_INCOMPATIBLE);
        assertThat(BatchPaymentEligibility.evaluate(true, ExpenseStatus.CANCELLED, 2, 2))
                .isEqualTo(BatchPaymentEligibility.STATE_INCOMPATIBLE);
        assertThat(BatchPaymentEligibility.evaluate(true, ExpenseStatus.PENDING, 3, 2))
                .isEqualTo(BatchPaymentEligibility.VERSION_CONFLICT);
        assertThat(BatchPaymentEligibility.evaluate(true, ExpenseStatus.PENDING, 0, -1))
                .isEqualTo(BatchPaymentEligibility.VERSION_CONFLICT);
    }
}
