package com.malyah.accountmanager.reporting.infrastructure;

import static com.malyah.accountmanager.reporting.infrastructure.MonthClosingPostgresIT.assertOctober;
import static com.malyah.accountmanager.reporting.infrastructure.MonthClosingPostgresIT.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ConfirmChargeCommand;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReportQueries;
import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.ClosingChangeView;
import com.malyah.accountmanager.reporting.application.ClosingSnapshotView;
import com.malyah.accountmanager.reporting.application.MonthClosingListView;
import com.malyah.accountmanager.reporting.application.MonthClosingView;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;

/**
 * H07.2 against real PostgreSQL: matrix D1–D17 of docs/evidencias/H07.2.md. Every test closes October (and
 * September when needed) with the H07.1 data, changes expenses through their own use cases and reads the month
 * again. Expected values were computed by hand before the implementation.
 */
class MonthClosingChangesPostgresIT extends MonthClosingTestSupport {
    static final LocalDate OCT_15 = LocalDate.of(2026, 10, 15);

    @Test
    void d1WithoutChangesTheClosingIsUpToDate() {
        matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        var view = view(A, "2026-10");
        assertThat(view.status()).isEqualTo("UP_TO_DATE");
        assertThat(view.changes()).isEmpty();
        assertThat(view.current().indicators()).isEqualTo(saved.indicators());
        assertThat(view.current().contentDigest()).isEqualTo(saved.contentDigest());
    }

    @Test
    void d2LabelsPaymentDatesWithADueDateAndTimeDoNotFlagTheClosing() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        var category = categories.list(A, false).stream().filter(c -> c.id().equals(housing)).findFirst()
                .orElseThrow();
        tx.execute(status -> categories.rename(A, housing, category.version(), "Lar e contas"));
        correctPending(data.o1, "Aluguel apto", "1500.00", LocalDate.of(2026, 10, 10), housing);
        correctPaid(data.o4, "Telefone", "110.00", LocalDate.of(2026, 9, 30), housing);

        var later = closings(new JdbcMonthClosingRepository(jdbc), new JdbcExpenseReportQueries(jdbc),
                Clock.fixed(Instant.parse("2026-10-25T15:00:00Z"), ZoneOffset.UTC)).view(A, "2026-10");

        assertThat(later.status()).isEqualTo("UP_TO_DATE");
        assertThat(later.changes()).isEmpty();
        assertThat(later.current().businessDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(later.current().indicators().overdueCount()).isEqualTo(4);
        assertThat(later.current().indicators().overdueTotal()).isEqualTo("1840.00");
        assertThat(later.current().indicators().overdueEstimated()).isEqualTo("180.00");
        assertThat(line(later.current(), data.o1).description()).isEqualTo("Aluguel apto");
        assertThat(line(later.current(), data.o1).categoryName()).isEqualTo("Lar e contas");
        assertPreserved(later, saved);
        assertThat(line(later.saved(), data.o1).description()).isEqualTo("Aluguel");
    }

    @Test
    void d3ALaterSettlementFlagsTheClosing() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        settle(data.o2, "100.00", OCT_15, GUEST);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("CHANGED", data.o2, List.of("SITUATION", "PAID_AMOUNT")));
        var change = view.changes().getFirst();
        assertThat(change.saved().status()).isEqualTo("PENDING");
        assertThat(change.saved().paidAmount()).isNull();
        assertThat(change.current().status()).isEqualTo("PAID");
        assertThat(change.current().paidAmount()).isEqualTo("100.00");
        assertTotals(view.current(), 8, "2792.33", "180.00", 5, "1062.33", 3, "1740.00", 2, "1560.00");
        assertPreserved(view, saved);
    }

    @Test
    void d4AReversalFlagsTheClosing() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        reverse(data.o4);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("CHANGED", data.o4, List.of("SITUATION", "PAID_AMOUNT")));
        assertThat(view.changes().getFirst().saved().paidAmount()).isEqualTo("110.00");
        assertThat(view.changes().getFirst().current().paidAmount()).isNull();
        assertTotals(view.current(), 8, "2792.33", "180.00", 3, "852.33", 5, "1960.00", 3, "1680.00");
        assertThat(view.current().indicators().adjustmentIncrease()).isEqualTo("20.00");
        assertThat(view.current().indicators().adjustmentDiscount()).isEqualTo("0.00");
        assertPreserved(view, saved);
    }

    @Test
    void d5ASettlementUndoneByAReversalLeavesNoDifference() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        settle(data.o2, "100.00", OCT_15, GUEST);
        reverse(data.o2);

        var view = view(A, "2026-10");
        assertThat(view.status()).isEqualTo("UP_TO_DATE");
        assertThat(view.changes()).isEmpty();
        assertPreserved(view, saved);
    }

    @Test
    void d6ACancellationRemovesTheEntryFromTheCurrentData() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        cancel(data.o1);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("REMOVED", data.o1, List.of()));
        var removed = view.changes().getFirst();
        assertThat(removed.current()).isNull();
        assertThat(removed.saved().description()).isEqualTo("Aluguel");
        assertThat(removed.saved().chargeAmount()).isEqualTo("1500.00");
        assertThat(removed.saved().status()).isEqualTo("PENDING");
        assertTotals(view.current(), 7, "1292.33", "180.00", 4, "962.33", 3, "340.00", 1, "60.00");
        var house = view.current().categories().getFirst();
        assertThat(house.categoryName()).isEqualTo("Casa e contas");
        assertThat(house.count()).isEqualTo(2);
        assertThat(house.plannedTotal()).isEqualTo("180.00");
        assertThat(house.paidTotal()).isEqualTo("110.00");
        assertThat(house.pendingCount()).isEqualTo(1);
        assertThat(house.pendingTotal()).isEqualTo("60.00");
        assertPreserved(view, saved);
    }

    @Test
    void d7AnInclusionIsAddedToTheCurrentData() {
        matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        var gift = pending("Presente", "50.00", LocalDate.of(2026, 10, 25), null);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("ADDED", gift, List.of()));
        assertThat(view.changes().getFirst().saved()).isNull();
        assertThat(view.changes().getFirst().current().chargeAmount()).isEqualTo("50.00");
        assertTotals(view.current(), 9, "2842.33", "180.00", 4, "962.33", 5, "1890.00", 2, "1560.00");
        assertPreserved(view, saved);
    }

    @Test
    void d8ACorrectionOfValueAndCategoryIsReportedFieldByField() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        correctPending(data.o2, "Internet", "120.00", OCT_15, housing);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("CHANGED", data.o2, List.of("CHARGE", "CATEGORY")));
        var change = view.changes().getFirst();
        assertThat(change.saved().chargeAmount()).isEqualTo("100.00");
        assertThat(change.saved().categoryName()).isNull();
        assertThat(change.current().chargeAmount()).isEqualTo("120.00");
        assertThat(change.current().categoryName()).isEqualTo("Casa e contas");
        assertTotals(view.current(), 8, "2812.33", "180.00", 4, "962.33", 4, "1860.00", 2, "1560.00");
        var house = view.current().categories().getFirst();
        assertThat(house.count()).isEqualTo(4);
        assertThat(house.plannedTotal()).isEqualTo("1800.00");
        assertThat(house.paidTotal()).isEqualTo("110.00");
        assertThat(house.pendingCount()).isEqualTo(3);
        assertThat(house.pendingTotal()).isEqualTo("1680.00");
        var without = view.current().categories().getLast();
        assertThat(without.categoryId()).isNull();
        assertThat(without.count()).isEqualTo(4);
        assertThat(without.plannedTotal()).isEqualTo("1012.33");
        assertThat(without.plannedEstimated()).isEqualTo("180.00");
        assertThat(without.paidTotal()).isEqualTo("852.33");
        assertThat(without.pendingCount()).isEqualTo(1);
        assertThat(without.pendingTotal()).isEqualTo("180.00");
        assertPreserved(view, saved);
    }

    @Test
    void d9ADueDateMovedToAnotherMonthFlagsBothMonths() {
        var data = matrix();
        var september = close(A, "2026-09", true).closing().saved();
        var october = close(G, "2026-10", true).closing().saved();
        correctPending(data.s1, "Condomínio set", "800.00", LocalDate.of(2026, 10, 12), null);

        var sep = view(A, "2026-09");
        assertOutdated(sep, tuple("REMOVED", data.s1, List.of()));
        assertTotals(sep.current(), 2, "240.00", "0.00", 2, "245.00", 0, "0.00", 0, "0.00");
        assertThat(sep.saved()).isEqualTo(september);
        var oct = view(A, "2026-10");
        assertOutdated(oct, tuple("ADDED", data.s1, List.of()));
        assertThat(oct.changes().getFirst().current().chargeAmount()).isEqualTo("800.00");
        assertTotals(oct.current(), 9, "3592.33", "180.00", 4, "962.33", 5, "2640.00", 3, "2360.00");
        assertPreserved(oct, october);
    }

    @Test
    void d10APaymentDateMovedToAnotherMonthFlagsBothMonthsWhenThereIsNoDueDate() {
        var data = matrix();
        var september = close(A, "2026-09", true).closing().saved();
        var october = close(G, "2026-10", true).closing().saved();
        correctPaid(data.s4, "Farmácia set", "90.00", LocalDate.of(2026, 10, 3), null);

        var sep = view(A, "2026-09");
        assertOutdated(sep, tuple("REMOVED", data.s4, List.of()));
        assertTotals(sep.current(), 2, "950.00", "0.00", 1, "155.00", 1, "800.00", 1, "800.00");
        assertThat(sep.saved()).isEqualTo(september);
        var oct = view(A, "2026-10");
        assertOutdated(oct, tuple("ADDED", data.s4, List.of()));
        assertThat(oct.changes().getFirst().current().status()).isEqualTo("PAID");
        assertTotals(oct.current(), 9, "2882.33", "180.00", 5, "1052.33", 4, "1840.00", 2, "1560.00");
        assertPreserved(oct, october);
    }

    @Test
    void d11ADueDateMovedInsideTheMonthIsAChangeOfTheSummary() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        correctPending(data.o2, "Internet", "100.00", LocalDate.of(2026, 10, 22), null);

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("CHANGED", data.o2, List.of("REFERENCE_DATE")));
        assertThat(view.changes().getFirst().saved().referenceDate()).isEqualTo(OCT_15);
        assertThat(view.changes().getFirst().current().referenceDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(view.current().indicators()).isEqualTo(saved.indicators());
        assertPreserved(view, saved);
    }

    @Test
    void d12ConfirmingAVariableChargeChangesValueAndEstimate() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        tx.execute(status -> expenses.confirmCharge(A, new ConfirmChargeCommand(data.o3, current(data.o3).version(),
                "175.00", UUID.randomUUID())));

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("CHANGED", data.o3, List.of("CHARGE", "ESTIMATE")));
        assertTotals(view.current(), 8, "2787.33", "0.00", 4, "962.33", 4, "1835.00", 2, "1560.00");
        assertThat(view.current().indicators().pendingEstimated()).isEqualTo("0.00");
        assertPreserved(view, saved);
    }

    @Test
    void d13NoChangeIsLostUnderConcurrency() throws Exception {
        var data = matrix();
        // (a) A settlement sent while October is being closed waits for the space lock and commits right after.
        var executor = Executors.newFixedThreadPool(2);
        var settlement = new AtomicReference<Future<?>>();
        var closing = closings(new JdbcMonthClosingRepository(jdbc), interceptingEntries(() -> {
            settlement.set(executor.submit(() -> settle(data.o2, "100.00", OCT_15, GUEST)));
            waitUntilBlockedOnTheSpaceLock();
        }), Clock.fixed(NOW, ZoneOffset.UTC));
        var saved = closing.close(G, new CloseMonthCommand("2026-10", true, UUID.randomUUID())).closing().saved();
        settlement.get().get(30, TimeUnit.SECONDS);
        assertThat(line(saved, data.o2).status()).isEqualTo("PENDING");
        assertOutdated(view(A, "2026-10"), tuple("CHANGED", data.o2, List.of("SITUATION", "PAID_AMOUNT")));

        // (b) Two members change two entries at the same moment: both differences are reported.
        reverse(data.o2);
        assertThat(view(A, "2026-10").status()).isEqualTo("UP_TO_DATE");
        var start = new CountDownLatch(1);
        var first = executor.submit(() -> {
            start.await();
            settle(data.o2, "100.00", OCT_15, GUEST);
            return null;
        });
        var second = executor.submit(() -> {
            start.await();
            cancel(data.o1);
            return null;
        });
        start.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        var view = view(A, "2026-10");
        assertOutdated(view, tuple("REMOVED", data.o1, List.of()),
                tuple("CHANGED", data.o2, List.of("SITUATION", "PAID_AMOUNT")));
        assertPreserved(view, saved);
    }

    @Test
    void d14EveryChangeLeavesTheSavedSnapshotAsItWas() {
        var data = matrix();
        var september = close(A, "2026-09", true).closing().saved();
        var october = close(G, "2026-10", true).closing().saved();
        var rows = closingRows();
        var stored = jdbc.queryForList("select * from month_closing_lines order by version_id, expense_id");
        settle(data.o2, "100.00", OCT_15, GUEST);
        reverse(data.o4);
        cancel(data.o1);
        pending("Presente", "50.00", LocalDate.of(2026, 10, 25), null);
        correctPending(data.s1, "Condomínio set", "800.00", LocalDate.of(2026, 10, 12),
                null);
        correctPaid(data.s4, "Farmácia set", "90.00", LocalDate.of(2026, 10, 3), null);
        tx.execute(status -> expenses.confirmCharge(A, new ConfirmChargeCommand(data.o3, current(data.o3).version(),
                "175.00", UUID.randomUUID())));

        assertThat(closingRows()).isEqualTo(rows);
        assertThat(jdbc.queryForList("select * from month_closing_lines order by version_id, expense_id"))
                .isEqualTo(stored);
        assertThat(view(A, "2026-09").saved()).isEqualTo(september);
        assertThat(view(A, "2026-10").saved()).isEqualTo(october);
        assertOctober(view(A, "2026-10").saved());
        assertThat(view(A, "2026-10").changes()).hasSize(7);
        assertThat(view(A, "2026-09").changes()).hasSize(2);
    }

    @Test
    void d15SpacesAreFlaggedIndependently() {
        var data = matrix();
        close(G, "2026-10", true);
        close(O, "2026-10", true);
        tx.execute(status -> expenses.settle(O, new com.malyah.accountmanager.expenses.application
                .SettleExpenseCommand(data.foreign, expenses.get(O, data.foreign).version(), "500.00", OCT_15,
                OUTSIDER, null, UUID.randomUUID())));

        assertThat(view(O, "2026-10").status()).isEqualTo("OUTDATED");
        assertThat(view(A, "2026-10").status()).isEqualTo("UP_TO_DATE");
        assertThat(closings.list(A, "2026").closings()).extracting(MonthClosingListView.Item::status)
                .containsExactly("UP_TO_DATE");
        assertThat(closings.list(O, "2026").closings()).extracting(MonthClosingListView.Item::status)
                .containsExactly("OUTDATED");
    }

    @Test
    void d16AMonthWithoutClosingIsNotClosed() {
        matrix();
        close(G, "2026-10", true);
        for (var month : List.of("2026-11", "2026-08")) {
            var view = view(A, month);
            assertThat(view.status()).isEqualTo("NOT_CLOSED");
            assertThat(view.saved()).isNull();
            assertThat(view.changes()).isEmpty();
        }
    }

    @Test
    void d17TheAnnualListShowsEveryClosedMonthWithItsSituation() {
        var data = matrix();
        close(A, "2026-09", true);
        close(G, "2026-10", true);
        settle(data.o2, "100.00", OCT_15, GUEST);

        var list = closings.list(G, "2026");
        assertThat(list.year()).isEqualTo(2026);
        assertThat(list.closings()).extracting(MonthClosingListView.Item::month, MonthClosingListView.Item::version,
                MonthClosingListView.Item::authorDisplayName, MonthClosingListView.Item::closedAt,
                MonthClosingListView.Item::status)
                .containsExactly(tuple("2026-09", 1, "Admin", NOW, "UP_TO_DATE"),
                        tuple("2026-10", 1, "Convidado", NOW, "OUTDATED"));
        assertThat(closings.list(A, null).year()).isEqualTo(2026);
        assertThat(closings.list(A, "2025").closings()).isEmpty();
        assertThatThrownBy(() -> closings.list(A, "26")).isInstanceOf(ReportQueryValidationException.class);
        // The list and the month view agree for every month.
        for (var item : list.closings())
            assertThat(view(A, item.month()).status()).isEqualTo(item.status());
    }

    private static void assertOutdated(MonthClosingView view, org.assertj.core.groups.Tuple... expected) {
        assertThat(view.status()).isEqualTo("OUTDATED");
        assertThat(view.changes()).extracting(ClosingChangeView::kind, ClosingChangeView::expenseId,
                ClosingChangeView::fields).containsExactly(expected);
        assertThat(view.current().contentDigest()).isNotEqualTo(view.saved().contentDigest());
    }

    /** The saved version is exactly the one returned by the closing: one version, same values, labels and digest. */
    private void assertPreserved(MonthClosingView view, ClosingSnapshotView saved) {
        assertThat(view.saved()).isEqualTo(saved);
        assertOctober(view.saved());
        assertThat(count("month_closing_versions")).isEqualTo(count("month_closings"));
    }

    private static void assertTotals(ClosingSnapshotView current, long planned, String plannedTotal,
            String plannedEstimated, long paid, String paidTotal, long pending, String pendingTotal, long overdue,
            String overdueTotal) {
        var totals = current.indicators();
        assertThat(totals.plannedCount()).isEqualTo(planned);
        assertThat(totals.plannedTotal()).isEqualTo(plannedTotal);
        assertThat(totals.plannedEstimated()).isEqualTo(plannedEstimated);
        assertThat(totals.paidCount()).isEqualTo(paid);
        assertThat(totals.paidTotal()).isEqualTo(paidTotal);
        assertThat(totals.pendingCount()).isEqualTo(pending);
        assertThat(totals.pendingTotal()).isEqualTo(pendingTotal);
        assertThat(totals.overdueCount()).isEqualTo(overdue);
        assertThat(totals.overdueTotal()).isEqualTo(overdueTotal);
    }
}
