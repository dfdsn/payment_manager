package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** H06.3 rules that do not depend on the database: indicators, horizon and the merged page. */
class PlanningDomainTest {
    private static final YearMonth JAN = YearMonth.of(2027, 1);

    private static PlanningLine materialized(PlanningOrigin origin, Situation situation, boolean estimated, long count,
            String charge, String paid) {
        return new PlanningLine(JAN, origin, false, situation, estimated, count, new BigDecimal(charge),
                new BigDecimal(paid));
    }

    @Test
    void totalsSplitConfirmationSourcePaymentAndOriginBySummingNeverBySubtracting() {
        var totals = PlanningTotals.of(List.of(
                materialized(PlanningOrigin.ONE_OFF, Situation.PAID, false, 1, "900.00", "880.00"),
                materialized(PlanningOrigin.INSTALLMENT, Situation.PENDING, false, 1, "333.33", "0"),
                materialized(PlanningOrigin.RECURRENCE, Situation.PENDING, true, 1, "210.00", "0"),
                PlanningLine.forecast(JAN, false, new BigDecimal("100.00")),
                PlanningLine.forecast(JAN, true, new BigDecimal("210.00"))));

        assertThat(totals.count()).isEqualTo(5);
        assertThat(totals.plannedTotal()).isEqualTo("1753.33");
        assertThat(totals.confirmedTotal()).isEqualTo("1333.33");
        assertThat(totals.estimatedTotal()).isEqualTo("420.00");
        assertThat(totals.materializedCount()).isEqualTo(3);
        assertThat(totals.materializedTotal()).isEqualTo("1443.33");
        assertThat(totals.forecastCount()).isEqualTo(2);
        assertThat(totals.forecastTotal()).isEqualTo("310.00");
        assertThat(totals.paidCount()).isEqualTo(1);
        assertThat(totals.paidTotal()).isEqualTo("880.00");
        assertThat(totals.openCount()).isEqualTo(4);
        assertThat(totals.openTotal()).isEqualTo("853.33");
        assertThat(totals.oneOffTotal()).isEqualTo("900.00");
        assertThat(totals.installmentTotal()).isEqualTo("333.33");
        assertThat(totals.recurrenceTotal()).isEqualTo("520.00");
    }

    @Test
    void emptyTotalsAreZeroWithTwoPlacesAndCountsAreAddedPerLine() {
        var empty = PlanningTotals.of(List.of());
        assertThat(empty.count()).isZero();
        assertThat(empty.plannedTotal()).isEqualTo("0.00");
        assertThat(empty.openTotal()).isEqualTo("0.00");
        var grouped = PlanningTotals.of(List.of(
                materialized(PlanningOrigin.ONE_OFF, Situation.PENDING, false, 130, "1301.30", "0"),
                materialized(PlanningOrigin.ONE_OFF, Situation.PAID, false, 7, "70.00", "71.00")));
        assertThat(grouped.count()).isEqualTo(137);
        assertThat(grouped.materializedCount()).isEqualTo(137);
        assertThat(grouped.openCount()).isEqualTo(130);
        assertThat(grouped.paidCount()).isEqualTo(7);
        assertThat(grouped.paidTotal()).isEqualTo("71.00");
        // Sums above the single charge limit keep every cent.
        var big = PlanningTotals.of(List.of(PlanningLine.forecast(JAN, false, new BigDecimal("99999999.99")),
                PlanningLine.forecast(JAN, false, new BigDecimal("99999999.99"))));
        assertThat(big.plannedTotal()).isEqualTo("199999999.98");
    }

    @Test
    void linesRejectInconsistentValues() {
        var zero = BigDecimal.ZERO;
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.ONE_OFF, true, Situation.PENDING, false, 1,
                BigDecimal.ONE, zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.RECURRENCE, true, Situation.PAID, false, 1,
                BigDecimal.ONE, zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.RECURRENCE, false, Situation.PENDING, false, 1,
                BigDecimal.ONE, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.RECURRENCE, false, Situation.PAID, false, -1,
                BigDecimal.ONE, zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.RECURRENCE, false, Situation.PAID, false, 1,
                BigDecimal.ONE.negate(), zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(JAN, PlanningOrigin.RECURRENCE, false, Situation.PAID, false, 1,
                BigDecimal.ONE, BigDecimal.ONE.negate())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningLine(null, PlanningOrigin.RECURRENCE, false, Situation.PAID, false, 1,
                BigDecimal.ONE, zero)).isInstanceOf(NullPointerException.class);
        assertThat(new PlanningLine(JAN, PlanningOrigin.INSTALLMENT, false, Situation.PAID, false, 0,
                BigDecimal.ONE, BigDecimal.TEN).paidTotal()).isEqualTo(BigDecimal.TEN);
        var forecast = PlanningLine.forecast(JAN, true, new BigDecimal("5.00"));
        assertThat(forecast.forecast()).isTrue();
        assertThat(forecast.origin()).isEqualTo(PlanningOrigin.RECURRENCE);
        assertThat(forecast.situation()).isEqualTo(Situation.PENDING);
        assertThat(forecast.count()).isEqualTo(1);
    }

    @Test
    void horizonIsTheCurrentMonthPlusTwelveAcrossTheYear() {
        var horizon = PlanningHorizon.from(YearMonth.of(2026, 10));
        assertThat(horizon.end()).isEqualTo(YearMonth.of(2027, 10));
        assertThat(horizon.months()).hasSize(13).startsWith(YearMonth.of(2026, 10)).endsWith(YearMonth.of(2027, 10));
        assertThat(horizon.contains(YearMonth.of(2026, 10))).isTrue();
        assertThat(horizon.contains(YearMonth.of(2027, 10))).isTrue();
        assertThat(horizon.contains(YearMonth.of(2026, 9))).isFalse();
        assertThat(horizon.contains(YearMonth.of(2027, 11))).isFalse();
        assertThatThrownBy(() -> new PlanningHorizon(YearMonth.of(2026, 10), YearMonth.of(2027, 9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageMergesByDateWithExpensesBeforeForecastsOfTheSameDay() {
        // January of the matrix: IPVA 20/01 and Sofá 31/01 (expenses); Internet 05/01, Streaming 12/01, Luz 20/01.
        var expenses = List.of(LocalDate.of(2027, 1, 20), LocalDate.of(2027, 1, 31));
        var forecasts = List.of(LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 12), LocalDate.of(2027, 1, 20));
        var perDay = new TreeMap<LocalDate, Long>();
        perDay.put(LocalDate.of(2027, 1, 20), 1L);
        perDay.put(LocalDate.of(2027, 1, 31), 1L);

        assertThat(PlanningPageMerge.page(0, 5, 0, expenses, forecasts, perDay)).containsExactly(
                new PlanningPageMerge.Slot(true, 0), new PlanningPageMerge.Slot(true, 1),
                new PlanningPageMerge.Slot(false, 0), new PlanningPageMerge.Slot(true, 2),
                new PlanningPageMerge.Slot(false, 1));
        assertThat(PlanningPageMerge.page(2, 2, 0, expenses, forecasts, perDay)).containsExactly(
                new PlanningPageMerge.Slot(false, 0), new PlanningPageMerge.Slot(true, 2));
        assertThat(PlanningPageMerge.page(4, 2, 1, expenses.subList(1, 2), forecasts, perDay))
                .containsExactly(new PlanningPageMerge.Slot(false, 0));
        assertThat(PlanningPageMerge.page(5, 2, 0, List.of(), forecasts, perDay)).isEmpty();
    }

    @Test
    void windowCoversEveryExpenseThatCanFallInThePage() {
        assertThat(PlanningPageMerge.windowStart(0, 3)).isZero();
        assertThat(PlanningPageMerge.windowStart(2, 3)).isZero();
        assertThat(PlanningPageMerge.windowStart(100, 3)).isEqualTo(97);
        assertThat(PlanningPageMerge.windowLimit(100, 50, 3)).isEqualTo(53);
        assertThat(PlanningPageMerge.windowLimit(0, 50, 3)).isEqualTo(50);
        assertThat(PlanningPageMerge.windowLimit(2, 2, 3)).isEqualTo(4);
    }
}
