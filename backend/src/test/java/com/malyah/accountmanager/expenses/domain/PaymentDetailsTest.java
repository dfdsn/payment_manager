package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDetailsTest {
    @Test void validatesDatesPayerMoneyAndObservationWithoutInventingDateRestrictions() {
        var payer = UUID.randomUUID();
        var date = LocalDate.of(2026, 10, 1);
        for (var amount : new String[] { "145", "155", "0.01", "99999999.99" })
            assertThat(new PaymentDetails(ExpenseAmount.parse(amount), date, payer, " pago ").notes()).isEqualTo("pago");
        for (var amount : new String[] { "0", "-1", "100000000", "1.001", "inválido" })
            assertThatThrownBy(() -> new PaymentDetails(ExpenseAmount.parse(amount), date, payer, null))
                    .isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new PaymentDetails(null, date, payer, null)).isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new PaymentDetails(ExpenseAmount.parse("1"), null, payer, null)).isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new PaymentDetails(ExpenseAmount.parse("1"), date, null, null)).isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new PaymentDetails(ExpenseAmount.parse("1"), date, payer, "x".repeat(2001))).isInstanceOf(ExpenseValidationException.class);
        assertThat(new PaymentDetails(ExpenseAmount.parse("1"), date, payer, "x".repeat(2000)).notes()).hasSize(2000);
        assertThat(new PaymentDetails(ExpenseAmount.parse("1"), date, payer, " ").notes()).isNull();
        assertThat(new PaymentDetails(ExpenseAmount.parse("1"), date, payer, null).canonical())
                .isNotEqualTo(new PaymentDetails(ExpenseAmount.parse("1"), date, payer, "null").canonical());
    }

    @Test void onlyPendingWithMatchingNonnegativeVersionCanBePaid() {
        assertThat(PaymentEligibility.eligible(ExpenseStatus.PENDING, 0, 0)).isTrue();
        assertThat(PaymentEligibility.eligible(ExpenseStatus.PENDING, 2, 2)).isTrue();
        assertThat(PaymentEligibility.eligible(ExpenseStatus.PAID, 0, 0)).isFalse();
        assertThat(PaymentEligibility.eligible(ExpenseStatus.PENDING, 1, 0)).isFalse();
        assertThat(PaymentEligibility.eligible(ExpenseStatus.PENDING, -1, -1)).isFalse();
    }
}
