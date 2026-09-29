package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class PaymentIndicatorsTest {
    private static TotalsBucket paid(long count, String charge, String paid, String increase, String discount) {
        return new TotalsBucket(Situation.PAID, false, false, count, new BigDecimal(charge), new BigDecimal(paid),
                new BigDecimal(increase), new BigDecimal(discount));
    }

    @Test
    void octoberPaymentsAddPaidChargesAndBothKindsOfAdjustment() {
        // H06.2 matrix P1: 6 active payments paid in October.
        var indicators = PaymentIndicators.from(List.of(paid(5, "1042.33", "1057.33", "25.00", "10.00"),
                paid(1, "120.00", "120.00", "0", "0")));
        assertThat(indicators.count()).isEqualTo(6);
        assertThat(indicators.paidTotal()).isEqualTo("1177.33");
        assertThat(indicators.chargeTotal()).isEqualTo("1162.33");
        assertThat(indicators.adjustmentIncrease()).isEqualTo("25.00");
        assertThat(indicators.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(indicators.adjustmentNet()).isEqualTo("15.00");
    }

    @Test
    void ca05AndDiscountHaveSignedAdjustmentsAndNoMonthIsEmptyOfZeros() {
        assertThat(PaymentIndicators.adjustment(new BigDecimal("150.00"), new BigDecimal("155.00"))).isEqualTo("5.00");
        assertThat(PaymentIndicators.adjustment(new BigDecimal("120.00"), new BigDecimal("110.00")))
                .isEqualTo("-10.00");
        assertThat(PaymentIndicators.adjustment(new BigDecimal("99.00"), new BigDecimal("99.00"))).isEqualTo("0.00");
        var empty = PaymentIndicators.from(List.of());
        assertThat(empty.count()).isZero();
        assertThat(empty.paidTotal()).isEqualTo("0.00");
        assertThat(empty.adjustmentNet()).isEqualTo("0.00");
        var discountOnly = PaymentIndicators.from(List.of(paid(1, "120.00", "110.00", "0", "10.00")));
        assertThat(discountOnly.adjustmentNet()).isEqualTo("-10.00");
    }

    @Test
    void totalsAboveTheSingleLimitAreExactAndPendingEntriesAreRefused() {
        var big = PaymentIndicators.from(List.of(paid(2, "199999999.98", "199999999.98", "0", "0"),
                paid(1, "0.01", "0.02", "0.01", "0")));
        assertThat(big.paidTotal()).isEqualTo("200000000.00");
        assertThat(big.chargeTotal()).isEqualTo("199999999.99");
        assertThatThrownBy(() -> PaymentIndicators.from(List.of(new TotalsBucket(Situation.PENDING, false, false, 1,
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentIndicators.adjustment(new BigDecimal("1.00"), new BigDecimal("1.005")))
                .isInstanceOf(ArithmeticException.class);
    }
}
