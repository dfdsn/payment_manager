package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.PaymentRecordPage;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.ReportedExpense;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReportQueries;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.ClosingIdempotencyConflictException;
import com.malyah.accountmanager.reporting.application.ClosingLineView;
import com.malyah.accountmanager.reporting.application.ClosingPendingConfirmationRequiredException;
import com.malyah.accountmanager.reporting.application.ClosingSnapshotView;
import com.malyah.accountmanager.reporting.application.MonthAlreadyClosedException;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;
import com.malyah.accountmanager.reporting.domain.ClosingMonthNotAllowedException;

/**
 * H07.1 against real PostgreSQL: matrix C1–C14 of docs/evidencias/H07.1.md. Expected values were computed by hand
 * from the rules, before the implementation.
 */
class MonthClosingPostgresIT extends MonthClosingTestSupport {

    @Test
    void c1GuestClosesOctoberWithTheIndependentlyComputedSummary() {
        var data = matrix();
        var result = close(G, "2026-10", true);

        assertThat(result.replayed()).isFalse();
        var saved = result.closing().saved();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.authorUserId()).isEqualTo(GUEST);
        assertThat(saved.authorDisplayName()).isEqualTo("Convidado");
        assertThat(saved.closedAt()).isEqualTo(NOW);
        assertThat(saved.businessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(saved.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(saved.pendingAcknowledged()).isTrue();
        assertOctober(saved);
        assertThat(saved.lines()).extracting(ClosingLineView::expenseId)
                .containsExactlyInAnyOrder(data.o1, data.o2, data.o3, data.o4, data.o5, data.o7, data.o8, data.o9)
                .doesNotContain(data.o6, data.foreign, data.purchase.installments().get(1).expenseId());
        var pending = saved.lines().stream().filter(line -> line.status().equals("PENDING")).toList();
        assertThat(pending).extracting(ClosingLineView::description).containsExactly("Gás", "Aluguel", "Internet", "Luz");
        assertThat(pending).extracting(ClosingLineView::overdue).containsExactly(true, true, false, false);
        assertThat(pending.getLast().estimated()).isTrue();
        var notebook = line(saved, data.o5);
        assertThat(notebook.origin()).isEqualTo("INSTALLMENT");
        assertThat(notebook.installmentNumber()).isEqualTo(1);
        assertThat(notebook.installmentCount()).isEqualTo(3);
        assertThat(line(saved, data.o7).adjustment()).isEqualTo("20.00");
        assertThat(line(saved, data.o4).adjustment()).isEqualTo("-10.00");

        // Same totals as the H06.1 dashboard of the month (rule reused, not re-implemented).
        var dashboard = reports.dueDashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                null)).indicators();
        assertThat(saved.indicators()).isEqualTo(dashboard);
        // Totals stored in their own columns match what the stored lines add up to.
        var stored = jdbc.queryForMap("select planned_total, paid_total, pending_total from month_closing_versions");
        assertThat(stored.get("planned_total")).isEqualTo(new BigDecimal("2792.33"));
        assertThat(sum(saved.lines(), false)).isEqualByComparingTo("2792.33");
        assertThat(sum(saved.lines(), true)).isEqualByComparingTo("962.33");
        assertThat(jdbc.queryForList("select event_type, actor_user_id, version_number from month_closing_events"))
                .singleElement().satisfies(event -> {
                    assertThat(event.get("event_type")).isEqualTo("MONTH_CLOSED");
                    assertThat(event.get("actor_user_id")).isEqualTo(GUEST);
                    assertThat(event.get("version_number")).isEqualTo(1);
                });
        // Reading the month later returns the same stored snapshot, for both roles.
        assertThat(view(A, "2026-10").saved()).isEqualTo(saved);
    }

    @Test
    void c2ClosingWithPendingEntriesRequiresTheWarningToBeConfirmed() {
        matrix();
        assertThatThrownBy(() -> close(G, "2026-10", false))
                .isInstanceOfSatisfying(ClosingPendingConfirmationRequiredException.class,
                        error -> assertThat(error.pendingCount()).isEqualTo(4));
        assertThat(closingRows().values()).containsOnly(0);
        assertThat(view(A, "2026-10").saved()).isNull();
    }

    @Test
    void c3AdminClosesSeptemberByDueDate() {
        var data = matrix();
        var saved = close(A, "2026-09", true).closing().saved();

        assertThat(saved.authorDisplayName()).isEqualTo("Admin");
        var totals = saved.indicators();
        assertThat(totals.plannedCount()).isEqualTo(3);
        assertThat(totals.plannedTotal()).isEqualTo("1040.00");
        assertThat(totals.paidCount()).isEqualTo(2);
        assertThat(totals.paidTotal()).isEqualTo("245.00");
        assertThat(totals.pendingCount()).isEqualTo(1);
        assertThat(totals.pendingTotal()).isEqualTo("800.00");
        assertThat(totals.overdueCount()).isEqualTo(1);
        assertThat(totals.overdueTotal()).isEqualTo("800.00");
        assertThat(totals.adjustmentIncrease()).isEqualTo("5.00");
        assertThat(totals.adjustmentDiscount()).isEqualTo("0.00");
        assertThat(totals.adjustmentNet()).isEqualTo("5.00");
        assertThat(saved.lines()).extracting(ClosingLineView::expenseId)
                .containsExactlyInAnyOrder(data.s1, data.s2, data.s4).doesNotContain(data.s3, data.o8);
        assertThat(line(saved, data.s2).paidAmount()).isEqualTo("155.00");
        var pharmacy = line(saved, data.s4);
        assertThat(pharmacy.referenceDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(pharmacy.dueDateInformed()).isFalse();
        assertThat(saved.categories()).singleElement().satisfies(category -> {
            assertThat(category.categoryId()).isNull();
            assertThat(category.plannedTotal()).isEqualTo("1040.00");
            assertThat(category.paidTotal()).isEqualTo("245.00");
            assertThat(category.pendingTotal()).isEqualTo("800.00");
        });
    }

    @Test
    void c4ClosingChangesNoExpenseAndLaterCorrectionsRemainAllowed() {
        var data = matrix();
        var before = expenseState();
        close(G, "2026-10", true);
        assertThat(expenseState()).isEqualTo(before);
        var dashboard = reports.dueDashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                null)).indicators();
        assertThat(dashboard.pendingCount()).isEqualTo(4);
        assertThat(dashboard.overdueCount()).isEqualTo(2);
        assertThat(current(data.o1).status()).isEqualTo(ExpenseStatus.PENDING);
        settle(data.o1, "1500.00", LocalDate.of(2026, 10, 15), ADMIN);
        assertThat(current(data.o1).status()).isEqualTo(ExpenseStatus.PAID);
    }

    @Test
    void c5RepeatedRequestsReturnTheFirstClosingAndReusedKeysWithOtherRequestsConflict() {
        matrix();
        var key = UUID.randomUUID();
        var first = close(G, "2026-10", true, key);
        var repeated = close(G, "2026-10", true, key);
        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated.closing().saved()).isEqualTo(first.closing().saved());
        assertThat(count("month_closing_versions")).isEqualTo(1);
        assertThat(count("month_closing_events")).isEqualTo(1);
        assertThatThrownBy(() -> close(G, "2026-09", true, key))
                .isInstanceOf(ClosingIdempotencyConflictException.class);
        assertThat(count("month_closings")).isEqualTo(1);
    }

    @Test
    void c6AMonthIsClosedOnlyOnce() {
        matrix();
        close(G, "2026-10", true);
        assertThatThrownBy(() -> close(A, "2026-10", true)).isInstanceOf(MonthAlreadyClosedException.class);
        assertThat(count("month_closing_versions")).isEqualTo(1);
        assertThat(count("month_closing_requests")).isEqualTo(1);
    }

    @Test
    void c7ConcurrentClosingsCreateOneSnapshot() throws Exception {
        matrix();
        var outcomes = race(() -> outcome(() -> close(A, "2026-10", true)), () -> outcome(() -> close(G, "2026-10",
                true)));
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "MonthAlreadyClosedException");
        assertThat(count("month_closings")).isEqualTo(1);
        assertThat(count("month_closing_versions")).isEqualTo(1);
        assertThat(count("month_closing_events")).isEqualTo(1);

        var key = UUID.randomUUID();
        var sameKey = race(() -> outcome(() -> close(A, "2026-09", true, key)),
                () -> outcome(() -> close(A, "2026-09", true, key)));
        assertThat(sameKey).containsExactlyInAnyOrder("CREATED", "REPLAYED");
        assertThat(count("month_closing_versions")).isEqualTo(2);
    }

    @Test
    void c8FutureMonthsAndMalformedMonthsAreRefusedInTheSpaceTimeZone() {
        matrix();
        assertThatThrownBy(() -> close(A, "2026-11", false)).isInstanceOf(ClosingMonthNotAllowedException.class);
        assertThatThrownBy(() -> close(A, "2026-13", false)).isInstanceOf(ReportQueryValidationException.class);
        assertThatThrownBy(() -> close(A, "out/2026", false)).isInstanceOf(ReportQueryValidationException.class);
        assertThat(view(A, "2026-11").closable()).isFalse();
        assertThat(view(A, "2026-10").closable()).isTrue();
        assertThat(closingRows().values()).containsOnly(0);

        // 02:30 UTC on 01/11 is still 31/10 in São Paulo.
        var lateNight = closings(new JdbcMonthClosingRepository(jdbc), new JdbcExpenseReportQueries(jdbc),
                Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"), ZoneOffset.UTC));
        var command = new com.malyah.accountmanager.reporting.application.CloseMonthCommand("2026-11", false,
                UUID.randomUUID());
        assertThatThrownBy(() -> lateNight.close(A, command)).isInstanceOf(ClosingMonthNotAllowedException.class);
        assertThat(lateNight.view(A, null).month()).isEqualTo("2026-10");
        var october = lateNight.close(A, new com.malyah.accountmanager.reporting.application.CloseMonthCommand(
                "2026-10", true, UUID.randomUUID())).closing().saved();
        assertThat(october.businessDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        // On 31/10 the entries due on 15/10 and 20/10 are overdue as well.
        assertThat(october.indicators().overdueCount()).isEqualTo(4);
    }

    @Test
    void c9AnEmptyMonthClosesWithZeroTotalsAndNoWarning() {
        matrix();
        var saved = close(A, "2026-08", false).closing().saved();
        assertThat(saved.lines()).isEmpty();
        assertThat(saved.categories()).isEmpty();
        assertThat(saved.indicators().plannedCount()).isZero();
        assertThat(saved.indicators().plannedTotal()).isEqualTo("0.00");
        assertThat(saved.indicators().pendingTotal()).isEqualTo("0.00");
        assertThat(saved.pendingAcknowledged()).isFalse();
    }

    @Test
    void c10SpacesAreIsolatedAndOnlyActiveMembersClose() {
        matrix();
        close(G, "2026-10", true);
        var foreign = close(O, "2026-10", true).closing().saved();
        assertThat(foreign.indicators().plannedCount()).isEqualTo(1);
        assertThat(foreign.indicators().plannedTotal()).isEqualTo("500.00");
        assertThat(foreign.authorDisplayName()).isEqualTo("Outro");
        var own = view(A, "2026-10").saved();
        assertOctober(own);
        assertThat(own.lines()).extracting(ClosingLineView::description).doesNotContain("Outro espaço");
        assertThatThrownBy(() -> close("nobody@example.com", "2026-10", true))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> view("nobody@example.com", "2026-10"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    @Test
    void c11AFailureWhileWritingTheAuditLeavesNothingAndTheSameKeyWorksAgain() {
        matrix();
        var failing = closings(new FailingAuditRepository(new JdbcMonthClosingRepository(jdbc)),
                new JdbcExpenseReportQueries(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));
        var key = UUID.randomUUID();
        var command = new com.malyah.accountmanager.reporting.application.CloseMonthCommand("2026-10", true, key);
        assertThatThrownBy(() -> failing.close(G, command)).hasMessageContaining("simulated audit failure");
        assertThat(closingRows().values()).containsOnly(0);

        var retried = close(G, "2026-10", true, key);
        assertThat(retried.replayed()).isFalse();
        assertOctober(retried.closing().saved());
        assertThat(count("month_closing_events")).isEqualTo(1);
    }

    @Test
    void c12TheSnapshotKeepsTheLabelsAndValuesOfTheClosingMoment() {
        var data = matrix();
        var saved = close(G, "2026-10", true).closing().saved();
        var category = categories.list(A, false).stream().filter(c -> c.id().equals(housing)).findFirst().orElseThrow();
        tx.execute(status -> categories.rename(A, housing, category.version(), "Lar e contas"));
        correctPending(data.o1, "Aluguel apto", "1500.00", LocalDate.of(2026, 10, 10), housing);
        settle(data.o2, "100.00", LocalDate.of(2026, 10, 15), GUEST);

        var later = view(A, "2026-10");
        assertThat(later.saved()).isEqualTo(saved);
        assertThat(line(later.saved(), data.o1).description()).isEqualTo("Aluguel");
        assertThat(line(later.saved(), data.o1).categoryName()).isEqualTo("Casa e contas");
        assertThat(line(later.saved(), data.o2).status()).isEqualTo("PENDING");
        assertOctober(later.saved());
        // The current data are recalculated apart and never replace the snapshot.
        assertThat(line(later.current(), data.o1).description()).isEqualTo("Aluguel apto");
        assertThat(line(later.current(), data.o1).categoryName()).isEqualTo("Lar e contas");
        assertThat(later.current().indicators().pendingCount()).isEqualTo(3);
        // The database itself refuses to rewrite a stored snapshot.
        assertThatThrownBy(() -> jdbc.update("update month_closing_lines set description = 'x'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from month_closing_versions"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
    }

    @Test
    void c13TotalsAboveTheSingleChargeLimitAreStoredExactly() {
        for (var description : List.of("Grande 1", "Grande 2"))
            create(A, new CreateOneOffExpenseCommand(description, "99999999.99", ExpenseStatus.PAID, null,
                    LocalDate.of(2026, 7, 10), null, UUID.randomUUID()));
        var saved = close(A, "2026-07", false).closing().saved();
        assertThat(saved.indicators().plannedTotal()).isEqualTo("199999999.98");
        assertThat(saved.indicators().paidTotal()).isEqualTo("199999999.98");
        assertThat(view(G, "2026-07").saved().indicators().paidTotal()).isEqualTo("199999999.98");
    }

    @Test
    void c14AChangeCommittedWhileClosingIsNotPartOfTheSnapshot() throws Exception {
        var data = matrix();
        var executor = Executors.newSingleThreadExecutor();
        var settlement = new AtomicReference<java.util.concurrent.Future<?>>();
        var delegate = new JdbcExpenseReportQueries(jdbc);
        var intercepting = new ExpenseReportQueries() {
            @Override
            public List<com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket> totals(UUID spaceId,
                    ExpenseSelection selection) {
                return delegate.totals(spaceId, selection);
            }

            @Override
            public PaymentRecordPage payments(UUID spaceId, ExpenseSelection selection, int page, int size,
                    PaymentSort sort, SortDirection direction) {
                return delegate.payments(spaceId, selection, page, size, sort, direction);
            }

            @Override
            public List<ReportedExpense> entries(UUID spaceId, ExpenseSelection selection) {
                var rows = delegate.entries(spaceId, selection);
                if (settlement.get() == null) {
                    // A member settles an October entry right after the closing read the month.
                    settlement.set(executor.submit(() -> settle(data.o2, "100.00", LocalDate.of(2026, 10, 15),
                            GUEST)));
                    waitUntilBlockedOnTheSpaceLock();
                }
                return rows;
            }
        };
        var closing = closings(new JdbcMonthClosingRepository(jdbc), intercepting, Clock.fixed(NOW, ZoneOffset.UTC));
        var saved = closing.close(G, new com.malyah.accountmanager.reporting.application.CloseMonthCommand("2026-10",
                true, UUID.randomUUID())).closing().saved();
        settlement.get().get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertOctober(saved);
        assertThat(line(saved, data.o2).status()).isEqualTo("PENDING");
        assertThat(sum(saved.lines(), false)).isEqualByComparingTo(saved.indicators().plannedTotal());
        assertThat(current(data.o2).status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(view(A, "2026-10").current().indicators().pendingCount()).isEqualTo(3);
    }

    static void assertOctober(ClosingSnapshotView saved) {
        var totals = saved.indicators();
        assertThat(totals.plannedCount()).isEqualTo(8);
        assertThat(totals.plannedTotal()).isEqualTo("2792.33");
        assertThat(totals.plannedEstimated()).isEqualTo("180.00");
        assertThat(totals.paidCount()).isEqualTo(4);
        assertThat(totals.paidTotal()).isEqualTo("962.33");
        assertThat(totals.pendingCount()).isEqualTo(4);
        assertThat(totals.pendingTotal()).isEqualTo("1840.00");
        assertThat(totals.pendingEstimated()).isEqualTo("180.00");
        assertThat(totals.overdueCount()).isEqualTo(2);
        assertThat(totals.overdueTotal()).isEqualTo("1560.00");
        assertThat(totals.overdueEstimated()).isEqualTo("0.00");
        assertThat(totals.adjustmentIncrease()).isEqualTo("20.00");
        assertThat(totals.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(totals.adjustmentNet()).isEqualTo("10.00");
        assertThat(saved.categories()).hasSize(2);
        var house = saved.categories().getFirst();
        assertThat(house.categoryName()).isEqualTo("Casa e contas");
        assertThat(house.count()).isEqualTo(3);
        assertThat(house.plannedTotal()).isEqualTo("1680.00");
        assertThat(house.plannedEstimated()).isEqualTo("0.00");
        assertThat(house.paidTotal()).isEqualTo("110.00");
        assertThat(house.pendingCount()).isEqualTo(2);
        assertThat(house.pendingTotal()).isEqualTo("1560.00");
        var without = saved.categories().getLast();
        assertThat(without.categoryId()).isNull();
        assertThat(without.categoryName()).isNull();
        assertThat(without.count()).isEqualTo(5);
        assertThat(without.plannedTotal()).isEqualTo("1112.33");
        assertThat(without.plannedEstimated()).isEqualTo("180.00");
        assertThat(without.paidTotal()).isEqualTo("852.33");
        assertThat(without.pendingCount()).isEqualTo(2);
        assertThat(without.pendingTotal()).isEqualTo("280.00");
    }

    static ClosingLineView line(ClosingSnapshotView snapshot, UUID expenseId) {
        return snapshot.lines().stream().filter(line -> line.expenseId().equals(expenseId)).findFirst().orElseThrow();
    }

    static BigDecimal sum(List<ClosingLineView> lines, boolean paid) {
        return lines.stream().map(line -> paid ? line.paidAmount() : line.chargeAmount())
                .filter(java.util.Objects::nonNull).map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String outcome(Callable<com.malyah.accountmanager.reporting.application.CloseMonthResult> action) {
        try {
            return action.call().replayed() ? "REPLAYED" : "CREATED";
        } catch (Exception error) {
            return error.getClass().getSimpleName();
        }
    }

    /** Starts the actions together and returns their outcomes. */
    @SafeVarargs
    static List<String> race(Callable<String>... actions) throws Exception {
        var executor = Executors.newFixedThreadPool(actions.length);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (var action : actions) futures.add(executor.submit(() -> {
                start.await();
                return action.call();
            }));
            start.countDown();
            var outcomes = new ArrayList<String>();
            for (var future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Delegates everything and fails when the audit event is written, after version, lines and categories. */
    static final class FailingAuditRepository extends DelegatingClosingRepository {
        FailingAuditRepository(com.malyah.accountmanager.reporting.application.port.MonthClosingRepository delegate) {
            super(delegate);
        }

        @Override
        public void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId,
                Instant at) {
            throw new IllegalStateException("simulated audit failure");
        }
    }
}
