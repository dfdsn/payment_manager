package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.malyah.accountmanager.expenses.application.BatchSettlementCommand;
import com.malyah.accountmanager.expenses.application.BatchSettlementItem;
import com.malyah.accountmanager.expenses.application.CancelExpenseCommand;
import com.malyah.accountmanager.expenses.application.CategoryService;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.ReversePaymentCommand;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReportQueries;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentExpenses;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCommand;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseView;
import com.malyah.accountmanager.installments.infrastructure.InstallmentTestFixtures;
import com.malyah.accountmanager.recurrences.application.CreateRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;
import com.malyah.accountmanager.recurrences.infrastructure.RecurrenceTestFixtures;
import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.PaymentReportQuery;
import com.malyah.accountmanager.reporting.application.PaymentReportView;
import com.malyah.accountmanager.reporting.application.PaymentRowView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportingService;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

/**
 * H06.1 (due-date dashboard) and H06.2 (payment view) against real PostgreSQL, with one-off expenses, recurrences
 * and installments created through their own use cases. Expected values come from the matrices in
 * docs/evidencias/H06.1.md and docs/evidencias/H06.2.md and were computed by hand.
 */
@Testcontainers
class ReportingPostgresIT {
    // 12:00 in São Paulo on 15/10/2026: entries due on 14/10 or before are overdue, due today are not.
    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID GUEST = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final String A = "admin@example.com";
    private static final String G = "guest@example.com";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_report_test").withUsername("account_manager")
            .withPassword("test-only-password");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ExpenseService expenses;
    private RecurrenceService recurrences;
    private InstallmentPurchaseService installments;
    private CategoryService categories;
    private ReportingUseCase reports;
    private UUID housing;

    @BeforeEach
    void reset() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(25);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", "other@example.com", OTHER, "ADMINISTRATOR");
        var context = new AuthenticatedUserContextService(contextRepository());
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var members = new JdbcFinancialMemberAccess(jdbc);
        var categoryRepository = new JdbcCategoryRepository(jdbc);
        categories = new CategoryService(categoryRepository, context, UUID::randomUUID, clock);
        expenses = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID, clock, members,
                categoryRepository);
        recurrences = RecurrenceTestFixtures.service(jdbc, context, categoryRepository, members, clock,
                new JdbcRecurringExpenseMaterializer(jdbc, tx), (email, command) -> expenses.confirmCharge(email, command));
        installments = InstallmentTestFixtures.purchases(jdbc, new JdbcInstallmentExpenses(jdbc, UUID::randomUUID),
                context, categoryRepository, members, clock);
        reports = new TransactionalReportingUseCase(new ReportingService(new JdbcExpenseReportQueries(jdbc), context,
                clock), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        housing = tx.execute(status -> categories.create(A, "Casa e contas").id());
    }

    /** Dataset of the matrix (scenarios S1–S4 and O1–O9) in its final state. */
    private Dataset matrix() {
        var data = new Dataset();
        data.s1 = pending("Condomínio set", "800.00", LocalDate.of(2026, 9, 20), null, null);
        var s2 = pending("Água set", "150.00", LocalDate.of(2026, 9, 25), null, null);
        settle(s2, "155.00", LocalDate.of(2026, 10, 2), ADMIN);
        var s3 = pending("Mercado set", "200.00", LocalDate.of(2026, 9, 28), null, null);
        cancel(s3);
        data.s4 = create(new CreateOneOffExpenseCommand("Farmácia set", "90.00", ExpenseStatus.PAID, null,
                LocalDate.of(2026, 9, 15), null, UUID.randomUUID()));

        data.o1 = pending("Aluguel out", "1500.00", LocalDate.of(2026, 10, 10), housing, ADMIN);
        data.o2 = pending("Internet out", "100.00", LocalDate.of(2026, 10, 15), null, GUEST);
        var light = recurrence("Luz", "180.00", RecurrenceValueType.VARIABLE_ESTIMATE, LocalDate.of(2026, 10, 20));
        data.o3 = anticipate(light, LocalDate.of(2026, 10, 20));
        data.o4 = pending("Telefone out", "120.00", LocalDate.of(2026, 10, 5), housing, null);
        settle(data.o4, "110.00", LocalDate.of(2026, 10, 6), GUEST);
        data.purchase = tx.execute(status -> installments.create(A, new InstallmentPurchaseCommand("Notebook",
                "1000.00", 3, LocalDate.of(2026, 10, 12), null, null, UUID.randomUUID())).purchase());
        var gas = recurrence("Gás", "60.00", RecurrenceValueType.FIXED, LocalDate.of(2026, 10, 8));
        data.o6 = anticipate(gas, LocalDate.of(2026, 10, 8));
        var parcel = data.purchase.installments().getFirst().expenseId();
        tx.execute(status -> expenses.settleBatch(G, new BatchSettlementCommand(List.of(
                new BatchSettlementItem(parcel, current(parcel).version()),
                new BatchSettlementItem(data.o6, current(data.o6).version())),
                LocalDate.of(2026, 10, 12), ADMIN, true, UUID.randomUUID())));
        data.o7 = pending("Seguro out", "250.00", LocalDate.of(2026, 10, 18), null, null);
        cancel(data.o7);
        data.o8 = pending("Escola out", "400.00", LocalDate.of(2026, 10, 3), null, null);
        settle(data.o8, "400.00", LocalDate.of(2026, 10, 4), ADMIN);
        reverse(data.o8);
        settle(data.o8, "420.00", LocalDate.of(2026, 10, 14), ADMIN);
        data.o9 = pending("Academia out", "99.00", LocalDate.of(2026, 10, 1), null, null);
        settle(data.o9, "99.00", LocalDate.of(2026, 9, 30), ADMIN);
        correctPayment(data.o9, "99.00", LocalDate.of(2026, 10, 1), GUEST);
        // Another space with an entry in October must never reach this space's totals.
        insertForeignExpense();
        return data;
    }

    @Test
    void octoberMatrixByDueDateMatchesTheIndependentlyComputedValues() {
        matrix();
        var october = dashboard(A, filters("2026-10"));

        assertThat(october.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(october.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(october.today()).isEqualTo(LocalDate.of(2026, 10, 15));
        var i = october.indicators();
        assertThat(i.plannedCount()).isEqualTo(8);
        assertThat(i.plannedTotal()).isEqualTo("2792.33");
        assertThat(i.plannedEstimated()).isEqualTo("180.00");
        assertThat(i.paidCount()).isEqualTo(5);
        assertThat(i.paidTotal()).isEqualTo("1022.33");
        assertThat(i.pendingCount()).isEqualTo(3);
        assertThat(i.pendingTotal()).isEqualTo("1780.00");
        assertThat(i.pendingEstimated()).isEqualTo("180.00");
        assertThat(i.overdueCount()).isEqualTo(1);
        assertThat(i.overdueTotal()).isEqualTo("1500.00");
        assertThat(i.overdueEstimated()).isEqualTo("0.00");
        assertThat(i.adjustmentIncrease()).isEqualTo("20.00");
        assertThat(i.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(i.adjustmentNet()).isEqualTo("10.00");
        assertThat(october.previousPending().count()).isEqualTo(1);
        assertThat(october.previousPending().total()).isEqualTo("800.00");
        assertThat(october.previousPending().overdueTotal()).isEqualTo("800.00");
        assertThat(october.previousPending().estimated()).isEqualTo("0.00");

        // M2: the September water bill paid in October belongs to September by due date, with its +5 adjustment.
        var september = dashboard(G, filters("2026-09")).indicators();
        assertThat(september.plannedCount()).isEqualTo(3);
        assertThat(september.plannedTotal()).isEqualTo("1040.00");
        assertThat(september.paidTotal()).isEqualTo("245.00");
        assertThat(september.pendingTotal()).isEqualTo("800.00");
        assertThat(september.overdueTotal()).isEqualTo("800.00");
        assertThat(september.adjustmentIncrease()).isEqualTo("5.00");
        assertThat(september.adjustmentDiscount()).isEqualTo("0.00");

        // M3: installments count in their own months; the purchase header is never an expense.
        var november = dashboard(A, filters("2026-11"));
        assertThat(november.indicators().plannedCount()).isEqualTo(1);
        assertThat(november.indicators().plannedTotal()).isEqualTo("333.33");
        assertThat(november.previousPending().total()).isEqualTo("2580.00");
        assertThat(dashboard(A, filters("2026-12")).indicators().plannedTotal()).isEqualTo("333.34");
        assertThat(jdbc.queryForObject("select count(*) from installment_purchases", Integer.class)).isEqualTo(1);
    }

    @Test
    void theTotalsAddExactlyTheEntriesListedWithTheSameFilters() {
        matrix();
        for (var filters : List.of(filters("2026-10"), new ReportFilters("2026-10", "out", null, false, null, false,
                null, ExpenseStatusFilter.ALL), new ReportFilters("2026-10", null, housing, false, null, false, null,
                ExpenseStatusFilter.ACTIVE))) {
            var listed = listAll(filters);
            var active = listed.stream().filter(e -> e.status() != ExpenseStatus.CANCELLED).toList();
            var indicators = dashboard(A, filters).indicators();
            assertThat(indicators.plannedCount()).isEqualTo(active.size());
            assertThat(new BigDecimal(indicators.plannedTotal())).isEqualByComparingTo(sum(active, false));
            assertThat(new BigDecimal(indicators.paidTotal())).isEqualByComparingTo(sum(active, true));
            assertThat(new BigDecimal(indicators.pendingTotal())).isEqualByComparingTo(sum(active.stream()
                    .filter(e -> e.status() == ExpenseStatus.PENDING).toList(), false));
        }
        // Listing cancelled entries (ALL) does not bring them into the totals: 250.00 of Seguro stays out.
        var all = listAll(new ReportFilters("2026-10", null, null, false, null, false, null, ExpenseStatusFilter.ALL));
        assertThat(all).extracting(ExpenseView::description).contains("Seguro out");
        assertThat(dashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                ExpenseStatusFilter.ALL)).indicators().plannedTotal()).isEqualTo("2792.33");
    }

    @Test
    void filtersNarrowEveryIndicatorConsistently() {
        matrix();
        var byCategory = dashboard(A, new ReportFilters("2026-10", null, housing, false, null, false, null, null));
        assertThat(byCategory.indicators().plannedTotal()).isEqualTo("1620.00");
        assertThat(byCategory.indicators().pendingTotal()).isEqualTo("1500.00");
        assertThat(byCategory.indicators().paidTotal()).isEqualTo("110.00");
        assertThat(byCategory.indicators().adjustmentDiscount()).isEqualTo("10.00");
        assertThat(byCategory.indicators().adjustmentIncrease()).isEqualTo("0.00");
        assertThat(byCategory.previousPending().total()).isEqualTo("0.00");

        var byGuestPayer = dashboard(A, new ReportFilters("2026-10", null, null, false, null, false, GUEST, null));
        assertThat(byGuestPayer.indicators().plannedTotal()).isEqualTo("219.00");
        assertThat(byGuestPayer.indicators().paidTotal()).isEqualTo("209.00");
        assertThat(byGuestPayer.indicators().pendingTotal()).isEqualTo("0.00");
        assertThat(byGuestPayer.previousPending().count()).isZero();

        var byResponsible = dashboard(A, new ReportFilters("2026-10", null, null, false, GUEST, false, null, null));
        assertThat(byResponsible.indicators().plannedTotal()).isEqualTo("100.00");
        assertThat(byResponsible.indicators().overdueTotal()).isEqualTo("0.00");

        var overdue = dashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                ExpenseStatusFilter.OVERDUE));
        assertThat(overdue.indicators().plannedTotal()).isEqualTo("1500.00");
        assertThat(overdue.indicators().pendingTotal()).isEqualTo("1500.00");
        assertThat(overdue.indicators().paidTotal()).isEqualTo("0.00");
        assertThat(overdue.previousPending().total()).isEqualTo("800.00");

        var paid = dashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                ExpenseStatusFilter.PAID));
        assertThat(paid.indicators().plannedTotal()).isEqualTo("1012.33");
        assertThat(paid.indicators().pendingTotal()).isEqualTo("0.00");
        assertThat(paid.previousPending().count()).isZero();

        var cancelled = dashboard(A, new ReportFilters("2026-10", null, null, false, null, false, null,
                ExpenseStatusFilter.CANCELLED));
        assertThat(cancelled.indicators().plannedCount()).isZero();
        assertThat(cancelled.indicators().plannedTotal()).isEqualTo("0.00");

        var search = dashboard(A, new ReportFilters("2026-10", "  LUZ ", null, false, null, false, null, null));
        assertThat(search.indicators().plannedTotal()).isEqualTo("180.00");
        assertThat(search.indicators().plannedEstimated()).isEqualTo("180.00");

        var withoutCategory = dashboard(A, new ReportFilters("2026-10", null, null, true, null, false, null, null));
        assertThat(withoutCategory.indicators().plannedTotal()).isEqualTo("1172.33");
    }

    @Test
    void reversalAndNewPaymentMoveTheEntryWithoutCountingItTwice() {
        var school = pending("Escola", "400.00", LocalDate.of(2026, 10, 3), null, null);
        settle(school, "400.00", LocalDate.of(2026, 10, 4), ADMIN);
        assertIndicators("400.00", "400.00", "0.00", "0.00", "0.00");

        reverse(school);
        assertIndicators("400.00", "0.00", "400.00", "400.00", "0.00");

        settle(school, "420.00", LocalDate.of(2026, 10, 14), GUEST);
        assertIndicators("400.00", "420.00", "0.00", "0.00", "20.00");
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?",
                Integer.class, school)).isEqualTo(3);
    }

    @Test
    void correctionsAndCancellationChangeTheTotalsOfTheMonthsInvolved() {
        var rent = pending("Aluguel", "1500.00", LocalDate.of(2026, 10, 10), null, null);
        correct(rent, "1600.00", LocalDate.of(2026, 10, 10));
        assertIndicators("1600.00", "0.00", "1600.00", "1600.00", "0.00");

        // Moving the due date to November moves the entry out of October and it is no longer overdue.
        correct(rent, "1600.00", LocalDate.of(2026, 11, 10));
        assertIndicators("0.00", "0.00", "0.00", "0.00", "0.00");
        var november = dashboard(A, filters("2026-11"));
        assertThat(november.indicators().pendingTotal()).isEqualTo("1600.00");
        assertThat(november.indicators().overdueTotal()).isEqualTo("0.00");

        cancel(rent);
        assertThat(dashboard(A, filters("2026-11")).indicators().plannedTotal()).isEqualTo("0.00");
        assertThat(dashboard(A, filters("2026-12")).previousPending().total()).isEqualTo("0.00");
    }

    @Test
    void totalsAboveTheSingleChargeLimitAreExactAndSpacesAreIsolated() {
        pending("Grande 1", "99999999.99", LocalDate.of(2026, 12, 1), null, null);
        pending("Grande 2", "99999999.99", LocalDate.of(2026, 12, 2), null, null);
        pending("Centavo", "0.01", LocalDate.of(2026, 12, 3), null, null);
        insertForeignExpense();

        var december = dashboard(A, filters("2026-12")).indicators();
        assertThat(december.plannedTotal()).isEqualTo("199999999.99");
        assertThat(december.pendingTotal()).isEqualTo("199999999.99");
        var foreign = dashboard("other@example.com", filters("2026-10")).indicators();
        assertThat(foreign.plannedTotal()).isEqualTo("500.00");
        assertThat(dashboard("other@example.com", filters("2026-12")).indicators().plannedCount()).isZero();
        // A category of another space selects nothing here instead of revealing foreign data.
        var foreignCategory = tx.execute(status -> categories.create("other@example.com", "Outra").id());
        assertThat(dashboard(A, new ReportFilters("2026-10", null, foreignCategory, false, null, false, null, null))
                .indicators().plannedCount()).isZero();
        assertThatThrownBy(() -> dashboard("nobody@example.com", filters("2026-10")))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        jdbc.update("update space_memberships set active=false, ended_at=?, ended_by_user_id=?, "
                + "end_reason='ADMIN_REMOVAL' where user_id=?", Timestamp.from(NOW), ADMIN, GUEST);
        assertThatThrownBy(() -> dashboard(G, filters("2026-10")))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    @Test
    void tenThousandEntriesAreSummedEntirelyInTheDatabase() {
        // 10,000 entries of 0.01 … 100.00 in October: sum = 0.01 × (10000 × 10001 / 2) = 500,050.00.
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, created_by_user_id, created_at, version)
                select gen_random_uuid(), ?, 'ONE_OFF', 'Carga ' || n, n * 0.01, true, 'PENDING',
                       date '2026-10-01' + (n % 31), date '2026-10-01' + (n % 31), ?, ?, 0
                  from generate_series(1, 10000) n
                """, SPACE, ADMIN, Timestamp.from(NOW));
        var started = System.nanoTime();
        var october = dashboard(A, filters("2026-10")).indicators();
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(october.plannedCount()).isEqualTo(10_000);
        assertThat(october.plannedTotal()).isEqualTo("500050.00");
        // Entries due from 01/10 to 14/10 (n % 31 between 0 and 13) are overdue on 15/10.
        var expectedOverdue = jdbc.queryForObject(
                "select sum(n * 0.01) from generate_series(1, 10000) n where n % 31 < 14", BigDecimal.class);
        assertThat(new BigDecimal(october.overdueTotal())).isEqualByComparingTo(expectedOverdue);
        System.out.println("H06.1 dashboard over 10,000 entries: " + elapsedMillis + " ms");
        assertThat(elapsedMillis).isLessThan(3_000);
    }

    // H06.2 — payment view (matrix P1–P15 in docs/evidencias/H06.2.md).

    @Test
    void octoberPaymentsMatchTheIndependentlyComputedMatrix() {
        var data = matrix();
        var october = payments(A, filters("2026-10"), 0, 20, PaymentSort.PAYMENT_DATE, SortDirection.ASC);

        assertThat(october.dateBasis()).isEqualTo("PAYMENT_DATE");
        assertThat(october.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        var i = october.indicators();
        assertThat(i.count()).isEqualTo(6);
        assertThat(i.paidTotal()).isEqualTo("1177.33");
        assertThat(i.chargeTotal()).isEqualTo("1162.33");
        assertThat(i.adjustmentIncrease()).isEqualTo("25.00");
        assertThat(i.adjustmentDiscount()).isEqualTo("10.00");
        assertThat(i.adjustmentNet()).isEqualTo("15.00");
        assertThat(october.totalElements()).isEqualTo(6);
        // Ordered by payment date, ties by description: O9, S2, O4, Gás, Notebook 1/3, O8.
        assertThat(october.content()).extracting(PaymentRowView::paymentDate).containsExactly(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14));
        assertThat(october.content()).extracting(PaymentRowView::expenseId).doesNotContain(data.s4, data.o7);

        // P2: the September water bill paid in October is in October here and in September by due date.
        var water = row(october, "Água set");
        assertThat(water.dueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(water.chargeAmount()).isEqualTo("150.00");
        assertThat(water.paidAmount()).isEqualTo("155.00");
        assertThat(water.adjustment()).isEqualTo("5.00");
        assertThat(water.payerDisplayName()).isEqualTo("Admin");
        assertThat(water.batchPayment()).isFalse();
        assertThat(water.lastCorrection()).isNull();
        assertThat(dashboard(A, filters("2026-09")).indicators().paidTotal()).isEqualTo("245.00");

        // P7/P8: batch and origins; the purchase header never appears, only its first installment.
        var parcel = october.content().stream().filter(r -> r.installment() != null).toList();
        assertThat(parcel).singleElement().satisfies(r -> {
            assertThat(r.origin()).isEqualTo("INSTALLMENT");
            assertThat(r.installment().number()).isEqualTo(1);
            assertThat(r.installment().count()).isEqualTo(3);
            assertThat(r.paidAmount()).isEqualTo("333.33");
            assertThat(r.batchPayment()).isTrue();
            assertThat(r.recordedByDisplayName()).isEqualTo("Convidado");
            assertThat(r.payerDisplayName()).isEqualTo("Admin");
        });
        var gas = october.content().stream().filter(r -> r.expenseId().equals(data.o6)).findFirst().orElseThrow();
        assertThat(gas.origin()).isEqualTo("RECURRENCE");
        assertThat(gas.batchPayment()).isTrue();
        assertThat(row(october, "Telefone out").adjustment()).isEqualTo("-10.00");
        assertThat(row(october, "Telefone out").batchPayment()).isFalse();
        assertThat(row(october, "Telefone out").categoryName()).isEqualTo("Casa e contas");

        // P5: corrected payer and the author of the correction are shown; the recorder stays the original one.
        var gym = row(october, "Academia out");
        assertThat(gym.payerDisplayName()).isEqualTo("Convidado");
        assertThat(gym.recordedByDisplayName()).isEqualTo("Admin");
        assertThat(gym.correctionCount()).isEqualTo(1);
        assertThat(gym.lastCorrection().actorDisplayName()).isEqualTo("Admin");
        assertThat(gym.lastCorrection().changedFields()).containsExactly("paymentDate", "paidByUserId");

        // P6: the reversed 400.00 payment of O8 is gone; the new 420.00 appears once with its +20.00.
        var school = row(october, "Escola out");
        assertThat(school.paidAmount()).isEqualTo("420.00");
        assertThat(school.paymentDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(school.lastCorrection()).isNull();
        assertThat(october.content()).filteredOn(r -> r.expenseId().equals(data.o8)).hasSize(1);

        var guestPayer = payments(A, new ReportFilters("2026-10", null, null, false, null, false, GUEST, null), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(guestPayer.indicators().count()).isEqualTo(2);
        assertThat(guestPayer.indicators().paidTotal()).isEqualTo("209.00");
        var adminPayer = payments(A, new ReportFilters("2026-10", null, null, false, null, false, ADMIN, null), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(adminPayer.indicators().count()).isEqualTo(4);
        assertThat(adminPayer.indicators().paidTotal()).isEqualTo("968.33");
        // September by payment keeps only the pharmacy paid without due date: O9 moved to October.
        var september = payments(G, filters("2026-09"), 0, 20, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(september.indicators().paidTotal()).isEqualTo("90.00");
        assertThat(september.content()).extracting(PaymentRowView::expenseId).containsExactly(data.s4);
        assertThat(september.content().getFirst().dueDate()).isNull();
    }

    @Test
    void correctionsOfDateValueAndPayerMoveAndTraceThePayment() {
        var pharmacy = create(new CreateOneOffExpenseCommand("Farmácia", "90.00", ExpenseStatus.PAID, null,
                LocalDate.of(2026, 9, 15), null, UUID.randomUUID()));
        var gym = pending("Academia", "99.00", LocalDate.of(2026, 10, 1), null, null);
        settle(gym, "99.00", LocalDate.of(2026, 9, 30), ADMIN);
        var phone = pending("Telefone", "120.00", LocalDate.of(2026, 10, 5), null, null);
        settle(phone, "110.00", LocalDate.of(2026, 10, 6), GUEST);
        assertThat(paymentsOf("2026-09").indicators().paidTotal()).isEqualTo("189.00");
        assertThat(paymentsOf("2026-10").indicators().paidTotal()).isEqualTo("110.00");

        // P3: correcting the payment date from 30/09 to 01/10 moves the payment across the month boundary.
        correctPayment(gym, "99.00", LocalDate.of(2026, 10, 1), GUEST);
        assertThat(paymentsOf("2026-09").indicators().paidTotal()).isEqualTo("90.00");
        assertThat(paymentsOf("2026-09").content()).extracting(PaymentRowView::expenseId).containsExactly(pharmacy);
        assertThat(paymentsOf("2026-10").indicators().paidTotal()).isEqualTo("209.00");

        // P4: correcting the paid value changes the paid total and the discount, and is traced on the row.
        correctPayment(phone, "115.00", LocalDate.of(2026, 10, 6), GUEST);
        var october = paymentsOf("2026-10");
        assertThat(october.indicators().paidTotal()).isEqualTo("214.00");
        assertThat(october.indicators().adjustmentDiscount()).isEqualTo("5.00");
        assertThat(october.indicators().adjustmentNet()).isEqualTo("-5.00");
        var phoneRow = row(october, "Telefone");
        assertThat(phoneRow.adjustment()).isEqualTo("-5.00");
        assertThat(phoneRow.payerDisplayName()).isEqualTo("Convidado");
        assertThat(phoneRow.lastCorrection().changedFields()).containsExactly("paidAmount");
        assertThat(phoneRow.lastCorrection().actorUserId()).isEqualTo(ADMIN);
        // A correction that does not touch the payment (description only) is not a payment correction.
        var expense = current(phone);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(phone, expense.version(),
                ExpenseStatus.PAID, "Telefone fixo", expense.amount(), expense.dueDate(), expense.notes(),
                expense.paidAmount(), expense.paymentDate(), expense.paidByUserId(), null, UUID.randomUUID())));
        assertThat(row(paymentsOf("2026-10"), "Telefone fixo").correctionCount()).isEqualTo(1);
        // Correcting the charge of a paid entry changes its adjustment and is shown as a payment correction.
        var renamed = current(phone);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(phone, renamed.version(),
                ExpenseStatus.PAID, renamed.description(), "115.00", renamed.dueDate(), renamed.notes(),
                renamed.paidAmount(), renamed.paymentDate(), renamed.paidByUserId(), null, UUID.randomUUID())));
        var corrected = row(paymentsOf("2026-10"), "Telefone fixo");
        assertThat(corrected.adjustment()).isEqualTo("0.00");
        assertThat(corrected.correctionCount()).isEqualTo(2);
        assertThat(corrected.lastCorrection().changedFields()).containsExactly("amount");
    }

    @Test
    void aReversedPaymentLeavesTheViewAndANewPaymentCountsOnce() {
        var school = pending("Escola", "400.00", LocalDate.of(2026, 10, 3), null, null);
        settle(school, "400.00", LocalDate.of(2026, 10, 4), ADMIN);
        correctPayment(school, "400.00", LocalDate.of(2026, 10, 5), ADMIN);
        assertThat(paymentsOf("2026-10").indicators().paidTotal()).isEqualTo("400.00");
        assertThat(row(paymentsOf("2026-10"), "Escola").correctionCount()).isEqualTo(1);

        reverse(school);
        var afterReversal = paymentsOf("2026-10");
        assertThat(afterReversal.indicators().count()).isZero();
        assertThat(afterReversal.indicators().paidTotal()).isEqualTo("0.00");
        assertThat(afterReversal.content()).isEmpty();

        settle(school, "420.00", LocalDate.of(2026, 10, 14), GUEST);
        var afterNewPayment = paymentsOf("2026-10");
        assertThat(afterNewPayment.indicators().count()).isEqualTo(1);
        assertThat(afterNewPayment.indicators().paidTotal()).isEqualTo("420.00");
        assertThat(afterNewPayment.indicators().adjustmentIncrease()).isEqualTo("20.00");
        var row = afterNewPayment.content().getFirst();
        assertThat(row.payerDisplayName()).isEqualTo("Convidado");
        // The correction belonged to the reversed payment, not to the active one.
        assertThat(row.correctionCount()).isZero();
        assertThat(row.lastCorrection()).isNull();
    }

    @Test
    void paymentFiltersPagesOrderingAndEmptyMonthsAreConsistent() {
        matrix();
        var housingPayments = payments(A, new ReportFilters("2026-10", null, housing, false, null, false, null, null),
                0, 20, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(housingPayments.indicators().count()).isEqualTo(1);
        assertThat(housingPayments.indicators().paidTotal()).isEqualTo("110.00");
        assertThat(housingPayments.indicators().adjustmentDiscount()).isEqualTo("10.00");
        var gas = payments(A, new ReportFilters("2026-10", " GÁS ", null, false, null, false, null, null), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(gas.indicators().paidTotal()).isEqualTo("60.00");
        assertThat(payments(A, new ReportFilters("2026-10", null, null, false, GUEST, false, null, null), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC).indicators().count()).isZero();
        assertThat(payments(A, new ReportFilters("2026-10", null, null, true, null, false, null, null), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC).indicators().paidTotal()).isEqualTo("1067.33");

        var first = payments(A, filters("2026-10"), 0, 4, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        var second = payments(A, filters("2026-10"), 1, 4, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(first.content()).hasSize(4);
        assertThat(second.content()).hasSize(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.indicators()).isEqualTo(first.indicators());
        var listed = new ArrayList<PaymentRowView>(first.content());
        listed.addAll(second.content());
        assertThat(listed).extracting(PaymentRowView::expenseId).doesNotHaveDuplicates();
        assertThat(listed.stream().map(r -> new BigDecimal(r.paidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(first.indicators().paidTotal());

        var byValue = payments(A, filters("2026-10"), 0, 20, PaymentSort.PAID_AMOUNT, SortDirection.DESC);
        assertThat(byValue.content()).extracting(PaymentRowView::paidAmount)
                .containsExactly("420.00", "333.33", "155.00", "110.00", "99.00", "60.00");
        var byDescription = payments(A, filters("2026-10"), 0, 20, PaymentSort.DESCRIPTION, SortDirection.ASC);
        assertThat(byDescription.content().getFirst().description()).isEqualTo("Academia out");
        assertThat(byDescription.sort()).isEqualTo("DESCRIPTION");

        var empty = payments(A, filters("2027-01"), 0, 20, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
        assertThat(empty.indicators().count()).isZero();
        assertThat(empty.indicators().paidTotal()).isEqualTo("0.00");
        assertThat(empty.indicators().adjustmentNet()).isEqualTo("0.00");
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalPages()).isZero();
    }

    @Test
    void paymentTotalsAreExactAboveTheSingleLimitAndSpacesAreIsolated() {
        for (var description : List.of("Grande 1", "Grande 2")) {
            var id = pending(description, "99999999.99", LocalDate.of(2026, 12, 1), null, null);
            settle(id, "99999999.99", LocalDate.of(2026, 12, 2), ADMIN);
        }
        assertThat(paymentsOf("2026-12").indicators().paidTotal()).isEqualTo("199999999.98");
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, payment_date, paid_amount, paid_by_user_id, payment_recorded_by_user_id,
                    payment_recorded_at, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Outro espaço', 500.00, true, 'PAID', date '2026-12-01', date '2026-12-01',
                    date '2026-12-02', 500.00, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), OTHER, OUTSIDER, OUTSIDER, Timestamp.from(NOW), OUTSIDER, Timestamp.from(NOW));
        assertThat(paymentsOf("2026-12").indicators().count()).isEqualTo(2);
        var foreign = payments("other@example.com", filters("2026-12"), 0, 20, PaymentSort.PAYMENT_DATE,
                SortDirection.ASC);
        assertThat(foreign.indicators().paidTotal()).isEqualTo("500.00");
        assertThat(foreign.content()).extracting(PaymentRowView::description).containsExactly("Outro espaço");
        var foreignCategory = tx.execute(status -> categories.create("other@example.com", "Outra").id());
        assertThat(payments(A, new ReportFilters("2026-12", null, foreignCategory, false, null, false, null, null), 0,
                20, PaymentSort.PAYMENT_DATE, SortDirection.ASC).indicators().count()).isZero();
        assertThatThrownBy(() -> paymentsOf("nobody@example.com", "2026-12"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    private PaymentReportView payments(String email, ReportFilters filters, int page, int size, PaymentSort sort,
            SortDirection direction) {
        return reports.payments(email, new PaymentReportQuery(filters, page, size, sort, direction));
    }

    private PaymentReportView paymentsOf(String month) {
        return paymentsOf(A, month);
    }

    private PaymentReportView paymentsOf(String email, String month) {
        return payments(email, filters(month), 0, 20, PaymentSort.PAYMENT_DATE, SortDirection.ASC);
    }

    private static PaymentRowView row(PaymentReportView view, String description) {
        return view.content().stream().filter(r -> r.description().equals(description)).findFirst().orElseThrow();
    }

    private void assertIndicators(String planned, String paid, String pending, String overdue, String net) {
        var indicators = dashboard(A, filters("2026-10")).indicators();
        assertThat(indicators.plannedTotal()).isEqualTo(planned);
        assertThat(indicators.paidTotal()).isEqualTo(paid);
        assertThat(indicators.pendingTotal()).isEqualTo(pending);
        assertThat(indicators.overdueTotal()).isEqualTo(overdue);
        assertThat(indicators.adjustmentNet()).isEqualTo(net);
    }

    private DueDashboardView dashboard(String email, ReportFilters filters) {
        return reports.dueDashboard(email, filters);
    }

    private static ReportFilters filters(String month) {
        return new ReportFilters(month, null, null, false, null, false, null, null);
    }

    private List<ExpenseView> listAll(ReportFilters filters) {
        var month = java.time.YearMonth.parse(filters.month());
        var result = new ArrayList<ExpenseView>();
        for (int page = 0; ; page++) {
            var content = expenses.list(A, new ExpenseListQuery(page, 3, ExpenseSort.REFERENCE_DATE, SortDirection.ASC,
                    filters.search(), month.atDay(1), month.atEndOfMonth(), ExpenseDateBasis.DUE_DATE,
                    filters.categoryId(), filters.withoutCategory(), filters.responsibleUserId(),
                    filters.withoutResponsible(), filters.payerUserId(),
                    filters.status() == null ? ExpenseStatusFilter.ACTIVE : filters.status(), null)).content();
            if (content.isEmpty()) return result;
            result.addAll(content);
        }
    }

    private static BigDecimal sum(List<ExpenseView> entries, boolean paid) {
        return entries.stream().map(e -> paid ? e.paidAmount() : e.amount()).filter(java.util.Objects::nonNull)
                .map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private UUID pending(String description, String amount, LocalDate due, UUID category, UUID responsible) {
        return create(new CreateOneOffExpenseCommand(description, amount, ExpenseStatus.PENDING, due, null, null,
                UUID.randomUUID(), null, null, null, category, responsible));
    }

    private UUID create(CreateOneOffExpenseCommand command) {
        return tx.execute(status -> expenses.create(A, command).expense().id());
    }

    private ExpenseView current(UUID id) {
        return expenses.get(A, id);
    }

    private void settle(UUID id, String paid, LocalDate date, UUID payer) {
        tx.execute(status -> expenses.settle(A, new SettleExpenseCommand(id, current(id).version(), paid, date, payer,
                null, UUID.randomUUID())));
    }

    private void reverse(UUID id) {
        tx.execute(status -> expenses.reversePayment(G, new ReversePaymentCommand(id, current(id).version(),
                "Quitação registrada por engano", UUID.randomUUID())));
    }

    private void cancel(UUID id) {
        tx.execute(status -> expenses.cancel(A, new CancelExpenseCommand(id, current(id).version(), "Não será cobrada",
                UUID.randomUUID())));
    }

    private void correct(UUID id, String amount, LocalDate due) {
        var expense = current(id);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(id, expense.version(), expense.status(),
                expense.description(), amount, due, expense.notes(), null, null, null, null, UUID.randomUUID())));
    }

    private void correctPayment(UUID id, String paid, LocalDate date, UUID payer) {
        var expense = current(id);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(id, expense.version(), ExpenseStatus.PAID,
                expense.description(), expense.amount(), expense.dueDate(), expense.notes(), paid, date, payer, null,
                UUID.randomUUID())));
    }

    private UUID recurrence(String description, String amount, RecurrenceValueType type, LocalDate first) {
        return tx.execute(status -> recurrences.create(A, new CreateRecurrenceCommand(description, amount, type,
                RecurrenceFrequency.MONTHLY, first, null, null, null, UUID.randomUUID())).recurrence().id());
    }

    private UUID anticipate(UUID recurrence, LocalDate due) {
        return tx.execute(status -> recurrences.anticipate(A, recurrence, due, UUID.randomUUID()).occurrence()
                .expenseId());
    }

    private void insertForeignExpense() {
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Outro espaço', 500.00, true, 'PENDING', date '2026-10-10', date '2026-10-10',
                    ?, ?, 0)
                """, UUID.randomUUID(), OTHER, OUTSIDER, Timestamp.from(NOW));
    }

    private AuthenticatedUserContextRepository contextRepository() {
        return email -> jdbc.query("""
                select u.id, u.display_name, u.normalized_email, s.id, s.name, m.role, s.currency_code, s.locale,
                       s.time_zone
                  from identity_users u
                  join space_memberships m on m.user_id = u.id and m.active = true
                  join family_spaces s on s.id = m.space_id
                 where u.normalized_email = ?
                """, (rs, row) -> new AuthenticatedUserContext(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), SpaceRole.valueOf(rs.getString(6)),
                rs.getString(7), rs.getString(8), rs.getString(9)), email).stream().findFirst();
    }

    private void insertSpace(UUID id, String name) {
        jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values "
                + "(?,?,'BRL','pt-BR','America/Sao_Paulo',?)", id, name, Timestamp.from(NOW));
    }

    private void insertUser(UUID id, String name, String email, UUID space, String role) {
        jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,"
                + "created_at) values (?,?,?,'{test}x',true,?)", id, name, email, Timestamp.from(NOW));
        jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values "
                + "(?,?,?,?,true,?)", UUID.randomUUID(), id, space, role, Timestamp.from(NOW));
    }

    private static final class Dataset {
        UUID s1, s4, o1, o2, o3, o4, o6, o7, o8, o9;
        InstallmentPurchaseView purchase;
    }
}
