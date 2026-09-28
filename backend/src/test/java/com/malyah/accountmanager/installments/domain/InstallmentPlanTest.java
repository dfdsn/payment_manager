package com.malyah.accountmanager.installments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InstallmentPlanTest {
    private static final LocalDate FIRST = LocalDate.of(2026, 10, 10);

    @Test
    void exactDivisionGivesEqualInstallmentsWithoutAdjustment() {
        var plan = InstallmentPlan.calculate("  Geladeira  ", new BigDecimal("1200.00"), 12, FIRST);

        assertThat(plan.description()).isEqualTo("Geladeira");
        assertThat(plan.count()).isEqualTo(12);
        assertThat(plan.installments()).extracting(Installment::amount).allSatisfy(a -> assertThat(a).isEqualByComparingTo("100.00"));
        assertThat(plan.installments()).extracting(Installment::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(plan.lastInstallmentAdjustment()).isEqualByComparingTo("0.00");
        assertThat(sum(plan)).isEqualByComparingTo(plan.total());
    }

    @Test
    void remainderCentsGoToTheLastInstallment() {
        var plan = InstallmentPlan.calculate("Sofá", new BigDecimal("100"), 3, FIRST);

        assertThat(plan.total()).isEqualTo(new BigDecimal("100.00"));
        assertThat(plan.installments()).extracting(i -> i.amount().toPlainString()).containsExactly("33.33", "33.33", "33.34");
        assertThat(plan.lastInstallmentAdjustment()).isEqualTo(new BigDecimal("0.01"));
        assertThat(sum(plan)).isEqualTo(new BigDecimal("100.00"));

        var larger = InstallmentPlan.calculate("TV", new BigDecimal("1000.00"), 7, FIRST);
        assertThat(larger.installments()).extracting(i -> i.amount().toPlainString())
                .containsExactly("142.85", "142.85", "142.85", "142.85", "142.85", "142.85", "142.90");
        assertThat(larger.lastInstallmentAdjustment()).isEqualTo(new BigDecimal("0.05"));
    }

    @ParameterizedTest
    @CsvSource({ "0.02,2", "0.05,2", "0.99,2", "1.00,3", "10.00,7", "99999999.99,360", "99999999.99,2", "123456.78,359",
            "3.60,360", "7.19,360", "0.10,3" })
    void sumIsAlwaysExactlyTheTotalAndEveryInstallmentIsPositive(String total, int count) {
        var plan = InstallmentPlan.calculate("Compra", new BigDecimal(total), count, FIRST);

        assertThat(plan.installments()).hasSize(count);
        assertThat(sum(plan)).isEqualTo(new BigDecimal(total));
        assertThat(plan.installments()).allSatisfy(i -> {
            assertThat(i.amount().scale()).isEqualTo(2);
            assertThat(i.amount()).isGreaterThanOrEqualTo(new BigDecimal("0.01"));
        });
        var regular = plan.installments().getFirst().amount();
        assertThat(plan.installments().subList(0, count - 1)).allSatisfy(i -> assertThat(i.amount()).isEqualTo(regular));
        assertThat(plan.installments().getLast().amount()).isGreaterThanOrEqualTo(regular);
        assertThat(plan.lastInstallmentAdjustment()).isLessThan(BigDecimal.valueOf(count, 2));
    }

    @Test
    void acceptsTheQuantityLimitsAndRejectsValuesOutsideThem() {
        assertThat(InstallmentPlan.calculate("Mínimo", new BigDecimal("0.02"), 2, FIRST).installments())
                .extracting(i -> i.amount().toPlainString()).containsExactly("0.01", "0.01");
        assertThat(InstallmentPlan.calculate("Máximo", new BigDecimal("3.60"), 360, FIRST).installments()).hasSize(360);

        for (Integer count : new Integer[] { null, 0, 1, 361, -2 })
            assertThatThrownBy(() -> InstallmentPlan.calculate("Compra", new BigDecimal("100.00"), count, FIRST))
                    .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("2 a 360")
                    .extracting("field").isEqualTo("installmentCount");
    }

    @Test
    void rejectsTotalsThatCannotGiveOneCentPerInstallment() {
        assertThatThrownBy(() -> InstallmentPlan.calculate("Chiclete", new BigDecimal("0.01"), 2, FIRST))
                .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("2 parcelas de pelo menos R$ 0,01")
                .extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> InstallmentPlan.calculate("Compra", new BigDecimal("3.59"), 360, FIRST))
                .hasMessageContaining("360 parcelas");
    }

    @Test
    void rejectsInvalidMonetaryValuesAndAcceptsTheApprovedLimits() {
        for (String invalid : new String[] { "0", "0.00", "-1.00", "10.001", "100000000.00", "99999999.991" })
            assertThatThrownBy(() -> InstallmentPlan.calculate("Compra", new BigDecimal(invalid), 2, FIRST))
                    .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("entre R$ 0,01 e R$ 99.999.999,99")
                    .extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> InstallmentPlan.calculate("Compra", null, 2, FIRST)).extracting("field").isEqualTo("totalAmount");
        assertThat(InstallmentPlan.calculate("Compra", new BigDecimal("99999999.99"), 2, FIRST).installments())
                .extracting(i -> i.amount().toPlainString()).containsExactly("49999999.99", "50000000.00");
        assertThat(InstallmentPlan.calculate("Compra", new BigDecimal("10.5"), 2, FIRST).total()).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void rejectsMissingOrTooLongDescriptionAndMissingFirstDueDate() {
        for (String invalid : new String[] { null, "", "   " })
            assertThatThrownBy(() -> InstallmentPlan.calculate(invalid, BigDecimal.TEN, 2, FIRST))
                    .isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("description");
        assertThat(InstallmentPlan.calculate("x".repeat(200), BigDecimal.TEN, 2, FIRST).description()).hasSize(200);
        assertThatThrownBy(() -> InstallmentPlan.calculate("x".repeat(201), BigDecimal.TEN, 2, FIRST))
                .extracting("field").isEqualTo("description");
        assertThatThrownBy(() -> InstallmentPlan.calculate("Compra", BigDecimal.TEN, 2, null))
                .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("primeira parcela")
                .extracting("field").isEqualTo("firstDueDate");
    }

    @Test
    void firstDueDateStartsTheMonthlyCalendarAndMonthEndsKeepTheOriginalDay() {
        var plan = InstallmentPlan.calculate("Notebook", new BigDecimal("600.00"), 6, LocalDate.of(2027, 11, 30));
        assertThat(plan.installments()).extracting(Installment::dueDate).containsExactly(LocalDate.of(2027, 11, 30),
                LocalDate.of(2027, 12, 30), LocalDate.of(2028, 1, 30), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 30),
                LocalDate.of(2028, 4, 30));

        var endOfMonth = InstallmentPlan.calculate("Curso", new BigDecimal("400.00"), 4, LocalDate.of(2027, 1, 31));
        assertThat(endOfMonth.installments()).extracting(Installment::dueDate).containsExactly(LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
        assertThat(endOfMonth.firstDueDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(endOfMonth.lastDueDate()).isEqualTo(LocalDate.of(2027, 4, 30));

        var leap = InstallmentPlan.calculate("Bicicleta", new BigDecimal("300.00"), 3, LocalDate.of(2028, 1, 29));
        assertThat(leap.installments()).extracting(Installment::dueDate).containsExactly(LocalDate.of(2028, 1, 29),
                LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 29));

        var yearChange = InstallmentPlan.calculate("Viagem", new BigDecimal("300.00"), 3, LocalDate.of(2026, 12, 15));
        assertThat(yearChange.installments()).extracting(Installment::dueDate).containsExactly(LocalDate.of(2026, 12, 15),
                LocalDate.of(2027, 1, 15), LocalDate.of(2027, 2, 15));
    }

    @Test
    void longPurchasesAreNotLimitedByTheForecastHorizonAndPastDatesAreKept() {
        var plan = InstallmentPlan.calculate("Imóvel", new BigDecimal("360000.00"), 360, LocalDate.of(2024, 2, 29));
        assertThat(plan.installments().get(12).dueDate()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(plan.installments().get(48).dueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(plan.lastDueDate()).isEqualTo(LocalDate.of(2054, 1, 29));
        assertThat(plan.installments().getLast().number()).isEqualTo(360);
    }

    private static BigDecimal sum(InstallmentPlan plan) {
        return plan.installments().stream().map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
