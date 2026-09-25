package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExpenseLifecycleEligibilityTest {
    @Test void validatesMandatoryNormalizedActionReason() {
        assertThat(new ExpenseActionReason("  lançamento duplicado  ").value()).isEqualTo("lançamento duplicado");
        assertThatThrownBy(() -> new ExpenseActionReason("   ")).isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new ExpenseActionReason("x".repeat(2001))).isInstanceOf(ExpenseValidationException.class);
    }

    @Test void reversalRequiresPaidExpenseAndExactVersion() {
        assertThat(PaymentReversalEligibility.eligible(ExpenseStatus.PAID, 3, 3)).isTrue();
        assertThat(PaymentReversalEligibility.eligible(ExpenseStatus.PENDING, 3, 3)).isFalse();
        assertThat(PaymentReversalEligibility.eligible(ExpenseStatus.CANCELLED, 3, 3)).isFalse();
        assertThat(PaymentReversalEligibility.eligible(ExpenseStatus.PAID, 4, 3)).isFalse();
    }

    @Test void cancellationRequiresPendingExpenseAndExactVersion() {
        assertThat(CancellationEligibility.eligible(ExpenseStatus.PENDING, 2, 2)).isTrue();
        assertThat(CancellationEligibility.eligible(ExpenseStatus.PAID, 2, 2)).isFalse();
        assertThat(CancellationEligibility.eligible(ExpenseStatus.CANCELLED, 2, 2)).isFalse();
        assertThat(CancellationEligibility.eligible(ExpenseStatus.PENDING, 3, 2)).isFalse();
    }
}
