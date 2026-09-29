package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** RF-FEC-01 content of a closing: H06.1 totals over the lines, categories, pending entries and the content digest. */
class ClosingSummaryTest {
    private static final UUID HOUSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID HEALTH = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    static ClosingLine pending(int id, String description, String charge, int day, boolean estimated, boolean overdue,
            UUID category, String categoryName) {
        return new ClosingLine(uuid(id), description, "ONE_OFF", null, null, LocalDate.of(2026, 10, day), true,
                Situation.PENDING, new BigDecimal(charge), estimated, null, overdue, category, categoryName);
    }

    static ClosingLine paid(int id, String description, String charge, String paidAmount, int day, UUID category,
            String categoryName) {
        return new ClosingLine(uuid(id), description, "ONE_OFF", null, null, LocalDate.of(2026, 10, day), true,
                Situation.PAID, new BigDecimal(charge), false, new BigDecimal(paidAmount), false, category,
                categoryName);
    }

    static UUID uuid(int id) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(id));
    }

    /** The October matrix of docs/evidencias/H07.1.md (C1), by hand. */
    static List<ClosingLine> october() {
        return List.of(
                pending(1, "Aluguel", "1500.00", 10, false, true, HOUSE, "Casa e contas"),
                pending(2, "Internet", "100.00", 15, false, false, null, null),
                pending(3, "Luz", "180.00", 20, true, false, null, null),
                paid(4, "Telefone", "120.00", "110.00", 5, HOUSE, "Casa e contas"),
                paid(5, "Notebook", "333.33", "333.33", 12, null, null),
                paid(7, "Escola", "400.00", "420.00", 3, null, null),
                paid(8, "Academia", "99.00", "99.00", 1, null, null),
                pending(9, "Gás", "60.00", 8, false, true, HOUSE, "Casa e contas"));
    }

    @Test
    void totalsFollowTheDueDashboardRulesOverTheSameLines() {
        var summary = ClosingSummary.of(october());
        var totals = summary.indicators();
        assertThat(totals.plannedCount()).isEqualTo(8);
        assertThat(totals.plannedTotal()).isEqualByComparingTo("2792.33");
        assertThat(totals.plannedEstimated()).isEqualByComparingTo("180.00");
        assertThat(totals.paidCount()).isEqualTo(4);
        assertThat(totals.paidTotal()).isEqualByComparingTo("962.33");
        assertThat(totals.pendingCount()).isEqualTo(4);
        assertThat(totals.pendingTotal()).isEqualByComparingTo("1840.00");
        assertThat(totals.pendingEstimated()).isEqualByComparingTo("180.00");
        assertThat(totals.overdueCount()).isEqualTo(2);
        assertThat(totals.overdueTotal()).isEqualByComparingTo("1560.00");
        assertThat(totals.adjustmentIncrease()).isEqualByComparingTo("20.00");
        assertThat(totals.adjustmentDiscount()).isEqualByComparingTo("10.00");
        assertThat(totals.plannedTotal().scale()).isEqualTo(2);
        assertThat(summary.hasPending()).isTrue();
        assertThat(summary.pendingLines()).extracting(ClosingLine::description)
                .containsExactly("Gás", "Aluguel", "Internet", "Luz");
        assertThat(summary.lines()).extracting(ClosingLine::description).containsExactly("Academia", "Escola",
                "Telefone", "Gás", "Aluguel", "Notebook", "Internet", "Luz");
    }

    @Test
    void categoriesAreAlphabeticalWithoutCategoryLast() {
        var lines = new java.util.ArrayList<>(october());
        lines.add(paid(10, "Remédio", "50.00", "45.00", 9, HEALTH, "Saúde"));
        lines.add(pending(11, "Consulta", "200.00", 25, true, false, HEALTH, "saúde bucal"));
        var categories = ClosingSummary.of(lines).categories();
        assertThat(categories).extracting(ClosingCategory::categoryName)
                .containsExactly("Casa e contas", "Saúde", null);
        var house = categories.getFirst();
        assertThat(house.count()).isEqualTo(3);
        assertThat(house.plannedTotal()).isEqualByComparingTo("1680.00");
        assertThat(house.paidTotal()).isEqualByComparingTo("110.00");
        assertThat(house.pendingCount()).isEqualTo(2);
        assertThat(house.pendingTotal()).isEqualByComparingTo("1560.00");
        var health = categories.get(1);
        assertThat(health.count()).isEqualTo(2);
        assertThat(health.plannedTotal()).isEqualByComparingTo("250.00");
        assertThat(health.plannedEstimated()).isEqualByComparingTo("200.00");
        assertThat(health.paidTotal()).isEqualByComparingTo("45.00");
        var without = categories.getLast();
        assertThat(without.categoryId()).isNull();
        assertThat(without.count()).isEqualTo(5);
        assertThat(without.plannedTotal()).isEqualByComparingTo("1112.33");
        assertThat(without.plannedEstimated()).isEqualByComparingTo("180.00");
        assertThat(without.paidTotal()).isEqualByComparingTo("852.33");
        assertThat(without.pendingCount()).isEqualTo(2);
        assertThat(without.pendingTotal()).isEqualByComparingTo("280.00");
    }

    @Test
    void anEmptyMonthHasZeroTotalsNoCategoriesAndNoPending() {
        var summary = ClosingSummary.of(List.of());
        assertThat(summary.lines()).isEmpty();
        assertThat(summary.categories()).isEmpty();
        assertThat(summary.hasPending()).isFalse();
        assertThat(summary.indicators().plannedTotal()).isEqualTo("0.00");
        assertThat(summary.digest()).hasSize(64).isEqualTo(ClosingSummary.of(List.of()).digest());
    }

    @Test
    void theDigestIgnoresOrderLabelsAndOverdueButNotValuesOrClassifications() {
        var base = ClosingSummary.of(october()).digest();
        var reversed = new java.util.ArrayList<>(october());
        java.util.Collections.reverse(reversed);
        assertThat(ClosingSummary.of(reversed).digest()).isEqualTo(base);

        assertThat(digestReplacing(1, pending(1, "Aluguel apto", "1500.00", 10, false, true, HOUSE, "Moradia")))
                .as("description and category name are labels").isEqualTo(base);
        assertThat(digestReplacing(2, pending(2, "Internet", "100.00", 15, false, true, null, null)))
                .as("overdue is a projection of time").isEqualTo(base);

        assertThat(digestReplacing(2, pending(2, "Internet", "100.01", 15, false, false, null, null)))
                .as("charge").isNotEqualTo(base);
        assertThat(digestReplacing(2, pending(2, "Internet", "100.00", 16, false, false, null, null)))
                .as("reference date").isNotEqualTo(base);
        assertThat(digestReplacing(2, pending(2, "Internet", "100.00", 15, true, false, null, null)))
                .as("estimate").isNotEqualTo(base);
        assertThat(digestReplacing(2, pending(2, "Internet", "100.00", 15, false, false, HEALTH, "Saúde")))
                .as("category").isNotEqualTo(base);
        assertThat(digestReplacing(2, paid(2, "Internet", "100.00", "100.00", 15, null, null)))
                .as("situation").isNotEqualTo(base);
        assertThat(digestReplacing(4, paid(4, "Telefone", "120.00", "111.00", 5, HOUSE, "Casa e contas")))
                .as("paid value").isNotEqualTo(base);
        assertThat(ClosingSummary.of(october().subList(0, 7)).digest()).as("removed entry").isNotEqualTo(base);
        // Same amount written with another scale is the same money.
        assertThat(digestReplacing(2, pending(2, "Internet", "100.0", 15, false, false, null, null))).isEqualTo(base);
    }

    private static String digestReplacing(int id, ClosingLine replacement) {
        return ClosingSummary.of(october().stream().map(line -> line.expenseId().equals(uuid(id)) ? replacement : line)
                .toList()).digest();
    }

    @Test
    void refusesTheSameEntryTwice() {
        var line = pending(1, "Aluguel", "1500.00", 10, false, true, HOUSE, "Casa e contas");
        assertThatThrownBy(() -> ClosingSummary.of(List.of(line, line))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linesAreConsistent() {
        var id = uuid(1);
        var day = LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PENDING,
                BigDecimal.ZERO, false, null, false, null, null)).hasMessageContaining("cobrança");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PENDING,
                BigDecimal.TEN, false, BigDecimal.TEN, false, null, null)).hasMessageContaining("pago");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PAID,
                BigDecimal.TEN, false, null, false, null, null)).hasMessageContaining("pago");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PAID,
                BigDecimal.TEN, false, BigDecimal.ZERO, false, null, null)).hasMessageContaining("positivo");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PAID,
                BigDecimal.TEN, false, BigDecimal.TEN, true, null, null)).hasMessageContaining("atrasado");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "INSTALLMENT", 1, null, day, true, Situation.PENDING,
                BigDecimal.TEN, false, null, false, null, null)).hasMessageContaining("Parcela");
        assertThatThrownBy(() -> new ClosingLine(id, "x", "ONE_OFF", null, null, day, true, Situation.PENDING,
                BigDecimal.TEN, false, null, false, HOUSE, null)).hasMessageContaining("Categoria");
        var installment = new ClosingLine(id, "x", "INSTALLMENT", 1, 3, day, true, Situation.PAID, BigDecimal.TEN,
                false, new BigDecimal("9"), false, HOUSE, "Casa");
        assertThat(installment.adjustment()).isEqualByComparingTo("-1");
        assertThat(pending(2, "y", "5.00", 2, false, false, null, null).adjustment()).isZero();
    }

    @Test
    void onlyStartedMonthsCanBeClosed() {
        var today = LocalDate.of(2026, 10, 31);
        assertThat(ClosingPeriod.closable(YearMonth.of(2026, 10), today)).isTrue();
        assertThat(ClosingPeriod.closable(YearMonth.of(2020, 1), today)).isTrue();
        assertThat(ClosingPeriod.closable(YearMonth.of(2026, 11), today)).isFalse();
        ClosingPeriod.requireClosable(YearMonth.of(2026, 10), today);
        assertThatThrownBy(() -> ClosingPeriod.requireClosable(YearMonth.of(2026, 11), today))
                .isInstanceOf(ClosingMonthNotAllowedException.class).hasMessageContaining("mês atual");
    }
}
