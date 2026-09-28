package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrectionEligibilityTest {
    @Test
    void acceptsOnlySameEditableStateAndExactNonNegativeVersion() {
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PENDING, ExpenseStatus.PENDING, 2, 2)).isTrue();
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PAID, ExpenseStatus.PAID, 4, 4)).isTrue();
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PENDING, ExpenseStatus.PAID, 2, 2)).isFalse();
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PAID, ExpenseStatus.PENDING, 2, 2)).isFalse();
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PENDING, ExpenseStatus.PENDING, 3, 2)).isFalse();
        assertThat(CorrectionEligibility.eligible(ExpenseStatus.PENDING, ExpenseStatus.PENDING, 0, -1)).isFalse();
    }
}
