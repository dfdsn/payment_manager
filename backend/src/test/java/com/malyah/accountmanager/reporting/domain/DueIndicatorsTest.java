package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Expected values are computed by hand from the H06.1 matrix (docs/evidencias/H06.1.md), not from the
 * implementation.
 */
class DueIndicatorsTest {
    private static TotalsBucket pending(boolean estimated, boolean overdue, long count, String charge) {
        return new TotalsBucket(Situation.PENDING, estimated, overdue, count, new BigDecimal(charge), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static TotalsBucket paid(boolean estimated, long count, String charge, String paid, String increase,
            String discount) {
        return new TotalsBucket(Situation.PAID, estimated, false, count, new BigDecimal(charge), new BigDecimal(paid),
                new BigDecimal(increase), new BigDecimal(discount));
    }

    @Test
    void octoberMatrixSeparatesPlannedPaidPendingOverdueAndAdjustments() {
        // M1: aluguel 1500 atrasado; internet 100 vence hoje; luz 180 estimada; cinco pagas.
        var indicators = DueIndicators.from(List.of(
                pending(false, true, 1, "1500.00"),
                pending(false, false, 1, "100.00"),
                pending(true, false, 1, "180.00"),
                paid(false, 5, "1012.33", "1022.33", "20.00", "10.00")));

        assertThat(indicators.plannedCount()).isEqualTo(8);
        assertThat(indicators.plannedTotal()).isEqualTo("2792.33");
        assertThat(indicators.plannedEstimated()).isEqualTo("180.00");
        assertThat(indicators.paidCount()).isEqualTo(5);
        assertThat(indicators.paidTotal()).isEqualTo("1022.33");
        assertThat(indicators.pendingCount()).isEqualTo(3);
        assertThat(indicators.pendingTotal()).isEqualTo("1780.00");
        assertThat(indicators.pendingEstimated()).isEqualTo("180.00");
        assertThat(indicators.overdueCount()).isEqualTo(1);
        assertThat(indicators.overdueTotal()).isEqualTo("1500.00");
        assertThat(indicators.overdueEstimated()).isEqualTo("0.00");
        assertThat(indicators.adjustmentIncrease()).isEqualTo("20.00");
        assertThat(indicators.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(indicators.adjustmentNet()).isEqualTo("10.00");
        // Pending is never planned minus paid: 2792.33 - 1022.33 = 1770.00, not 1780.00.
        assertThat(indicators.plannedTotal().subtract(indicators.paidTotal())).isNotEqualTo(indicators.pendingTotal());
    }

    @Test
    void chargeOf150PaidWith155LeavesNothingPendingAndAnIncreaseOf5() {
        var indicators = DueIndicators.from(List.of(paid(false, 1, "150.00", "155.00", "5.00", "0.00")));

        assertThat(indicators.plannedTotal()).isEqualTo("150.00");
        assertThat(indicators.paidTotal()).isEqualTo("155.00");
        assertThat(indicators.pendingTotal()).isEqualTo("0.00");
        assertThat(indicators.pendingCount()).isZero();
        assertThat(indicators.adjustmentIncrease()).isEqualTo("5.00");
        assertThat(indicators.adjustmentDiscount()).isEqualTo("0.00");
        assertThat(indicators.adjustmentNet()).isEqualTo("5.00");
    }

    @Test
    void aDiscountIsNegativeNetAndNeverLeavesABalance() {
        var indicators = DueIndicators.from(List.of(paid(false, 1, "120.00", "110.00", "0.00", "10.00")));

        assertThat(indicators.pendingTotal()).isEqualTo("0.00");
        assertThat(indicators.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(indicators.adjustmentNet()).isEqualTo("-10.00");
    }

    @Test
    void estimatedOverdueEntriesAreFlaggedInEveryIndicatorTheyBelongTo() {
        var indicators = DueIndicators.from(List.of(pending(true, true, 2, "360.00"),
                paid(true, 1, "90.00", "90.00", "0.00", "0.00")));

        assertThat(indicators.plannedEstimated()).isEqualTo("450.00");
        assertThat(indicators.pendingEstimated()).isEqualTo("360.00");
        assertThat(indicators.overdueEstimated()).isEqualTo("360.00");
        assertThat(indicators.overdueCount()).isEqualTo(2);
        assertThat(indicators.paidTotal()).isEqualTo("90.00");
    }

    @Test
    void emptySelectionHasZeroInEveryIndicatorWithTwoDecimals() {
        var indicators = DueIndicators.from(List.of());

        assertThat(indicators.plannedCount()).isZero();
        assertThat(indicators.plannedTotal().toPlainString()).isEqualTo("0.00");
        assertThat(indicators.paidTotal().toPlainString()).isEqualTo("0.00");
        assertThat(indicators.pendingTotal().toPlainString()).isEqualTo("0.00");
        assertThat(indicators.overdueTotal().toPlainString()).isEqualTo("0.00");
        assertThat(indicators.adjustmentNet().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void totalsAboveTheSingleChargeLimitKeepExactCents() {
        var indicators = DueIndicators.from(List.of(pending(false, false, 2, "199999999.98"),
                pending(false, true, 1, "0.01")));

        assertThat(indicators.pendingTotal().toPlainString()).isEqualTo("199999999.99");
        assertThat(indicators.plannedTotal().toPlainString()).isEqualTo("199999999.99");
        assertThat(indicators.overdueTotal().toPlainString()).isEqualTo("0.01");
    }

    @Test
    void moneyRefusesToDropCents() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
        assertThat(Money.of(new BigDecimal("7")).toPlainString()).isEqualTo("7.00");
    }

    @Test
    void bucketsRejectInconsistentData() {
        assertThatThrownBy(() -> new TotalsBucket(Situation.PAID, false, true, 1, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PENDING, false, false, 1, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PENDING, false, false, 1, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PENDING, false, false, 1, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PENDING, false, false, -1, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PENDING, false, false, 1, new BigDecimal("-0.01"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotalsBucket(null, false, false, 1, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TotalsBucket(Situation.PAID, false, false, 1, BigDecimal.ONE, null,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(NullPointerException.class);
        assertThat(new TotalsBucket(Situation.PENDING, true, true, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO).count()).isZero();
    }
}
