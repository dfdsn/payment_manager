package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BatchPaymentInstructionTest {
    @Test void createsAnIntegralPaymentFromTheConfirmedCharge() {
        var payer = UUID.randomUUID();
        var payment = new BatchPaymentInstruction(LocalDate.of(2026, 10, 1), payer)
                .paymentFor(new BigDecimal("123.45"));
        assertThat(payment.amount().canonical()).isEqualTo("123.45");
        assertThat(payment.date()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(payment.payerId()).isEqualTo(payer);
        assertThat(payment.notes()).isNull();
    }

    @Test void requiresDatePayerAndValidCharge() {
        assertThatThrownBy(() -> new BatchPaymentInstruction(null, UUID.randomUUID()))
                .isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new BatchPaymentInstruction(LocalDate.now(), null))
                .isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(() -> new BatchPaymentInstruction(LocalDate.now(), UUID.randomUUID())
                .paymentFor(new BigDecimal("1.001"))).isInstanceOf(ExpenseValidationException.class);
    }
}
