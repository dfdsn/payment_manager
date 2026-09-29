package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.malyah.accountmanager.expenses.application.CancelExpenseCommand;
import com.malyah.accountmanager.expenses.application.CategoryService;
import com.malyah.accountmanager.expenses.application.ConfirmChargeCommand;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.ReversePaymentCommand;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpensePlanningQueries;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentExpenses;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringOccurrenceAdjuster;
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
import com.malyah.accountmanager.recurrences.application.ChangeRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.CloseRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.CreateRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastCatalog;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;
import com.malyah.accountmanager.recurrences.infrastructure.JdbcRecurrenceGenerationJob;
import com.malyah.accountmanager.recurrences.infrastructure.RecurrenceTestFixtures;
import com.malyah.accountmanager.reporting.application.PlanningItemView;
import com.malyah.accountmanager.reporting.application.PlanningQuery;
import com.malyah.accountmanager.reporting.application.PlanningService;
import com.malyah.accountmanager.reporting.application.PlanningTotalsView;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.PlanningView;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;

/**
 * H06.3 against real PostgreSQL: one-off expenses, installments and recurrences created, generated, anticipated,
 * confirmed, corrected, paid, reversed, cancelled, changed and closed through their own use cases. Every expected
 * value comes from the matrix in docs/evidencias/H06.3.md, computed by hand before running.
 */
@Testcontainers
class PlanningPostgresIT {
    // 12:00 in São Paulo on 15/10/2026: the horizon is 10/2026 to 10/2027.
    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final UUID GUEST = UUID.fromString("80000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final String A = "admin@example.com";
    private static final String G = "guest@example.com";
    private static final String O = "other@example.com";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_planning_test").withUsername("account_manager")
            .withPassword("test-only-password");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private Clock clock;
    private ExpenseService expenses;
    private RecurrenceService recurrences;
    private InstallmentPurchaseService installments;
    private CategoryService categories;
    private PlanningUseCase planning;
    private UUID housing;

    @BeforeEach
    void reset() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(21);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        var context = new AuthenticatedUserContextService(contextRepository());
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var members = new JdbcFinancialMemberAccess(jdbc);
        var categoryRepository = new JdbcCategoryRepository(jdbc);
        categories = new CategoryService(categoryRepository, context, UUID::randomUUID, clock);
        expenses = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID, clock, members,
                categoryRepository);
        recurrences = RecurrenceTestFixtures.service(jdbc, context, categoryRepository, members, clock,
                new JdbcRecurringExpenseMaterializer(jdbc, tx), (email, command) -> expenses.confirmCharge(email, command),
                new JdbcRecurringOccurrenceAdjuster(jdbc));
        installments = InstallmentTestFixtures.purchases(jdbc, new JdbcInstallmentExpenses(jdbc, UUID::randomUUID),
                context, categoryRepository, members, clock);
        var forecasts = new RecurrenceForecastCatalog(RecurrenceTestFixtures.service(jdbc, context,
                categoryRepository, members, clock, new JdbcRecurringExpenseMaterializer(jdbc, tx), null));
        planning = new TransactionalPlanningUseCase(new PlanningService(new JdbcExpensePlanningQueries(jdbc),
                forecasts, context, clock), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        housing = tx.execute(status -> categories.create(A, "Casa e contas").id());
    }

    /** Scenarios P1–P11 of the matrix, in their final state. */
    private void matrix() {
        pending("Seguro carro", "1200.00", d(2026, 11, 10), housing);                       // P1
        var ipva = pending("IPVA", "900.00", d(2027, 1, 20), null);                          // P2
        settle(ipva, "880.00", d(2026, 10, 14));
        cancel(pending("Revisão", "300.00", d(2026, 11, 5), null));                          // P3
        pending("Limite fim", "10.00", d(2027, 10, 31), null);                               // P4
        pending("Fora do horizonte", "20.00", d(2027, 11, 1), null);
        pending("Mês anterior", "30.00", d(2026, 9, 30), null);

        var sofa = purchase("Sofá", "1000.00", 3, d(2026, 12, 31));                          // P5
        var third = sofa.installments().get(2).expenseId();
        correctDue(third, d(2027, 3, 5));

        var internet = recurrence("Internet", "100.00", RecurrenceValueType.FIXED, d(2026, 10, 5), housing); // P6
        var light = recurrence("Luz", "180.00", RecurrenceValueType.VARIABLE_ESTIMATE, d(2026, 10, 20), null); // P7
        anticipate(internet, d(2026, 10, 5));
        // The October job finds Internet already anticipated and generates only Luz.
        new JdbcRecurrenceGenerationJob(jdbc, tx, new JdbcRecurringExpenseMaterializer(jdbc, tx), clock,
                Duration.ofMinutes(2), 25).poll();
        var novInternet = anticipate(internet, d(2026, 11, 5));
        settle(novInternet, "100.00", d(2026, 10, 15));
        var octLight = occurrence(light, d(2026, 10, 20));
        tx.execute(s -> expenses.confirmCharge(A, new ConfirmChargeCommand(octLight, current(octLight).version(),
                "210.00", UUID.randomUUID())));
        var novLight = anticipate(light, d(2026, 11, 20));
        correctDue(novLight, d(2026, 12, 2));
        change(internet, d(2027, 3, 5), "120.00", housing);
        close(light, d(2027, 6, 20));

        var streaming = recurrence("Streaming", "40.00", RecurrenceValueType.FIXED, d(2026, 11, 12), null); // P8
        var novStreaming = anticipate(streaming, d(2026, 11, 12));
        settle(novStreaming, "40.00", d(2026, 10, 15));
        tx.execute(s -> expenses.reversePayment(G, new ReversePaymentCommand(novStreaming,
                current(novStreaming).version(), "Pago na conta errada", UUID.randomUUID())));
        cancel(anticipate(streaming, d(2026, 12, 12)));

        // P11: another space with its own expense and recurrence in the horizon.
        tx.execute(s -> expenses.create(O, new CreateOneOffExpenseCommand("Outro espaço", "500.00",
                ExpenseStatus.PENDING, d(2026, 11, 10), null, null, UUID.randomUUID())));
        tx.execute(s -> recurrences.create(O, new CreateRecurrenceCommand("Aluguel alheio", "700.00",
                RecurrenceValueType.FIXED, RecurrenceFrequency.MONTHLY, d(2026, 11, 1), null, null, null,
                UUID.randomUUID())));
    }

    @Test
    void horizonTotalsAndEveryMonthMatchTheIndependentlyComputedMatrix() {
        matrix();
        var before = rowCounts();

        var view = planning(A, query(null, null, null, false, 0, 20));

        assertThat(rowCounts()).as("opening the planning writes nothing").isEqualTo(before);
        assertThat(view.horizonStart()).isEqualTo("2026-10");
        assertThat(view.horizonEnd()).isEqualTo("2027-10");
        assertThat(view.periodEnd()).isEqualTo(d(2027, 10, 31));
        assertThat(view.today()).isEqualTo(d(2026, 10, 15));
        assertThat(view.month()).isEqualTo("2026-10");
        assertTotals(view.totals(), 39, "6900.00", "5220.00", "1680.00", 11, "3770.00", 28, "3130.00", 2, "980.00",
                37, "5900.00");
        assertThat(view.totals().oneOffTotal()).isEqualTo("2110.00");
        assertThat(view.totals().installmentTotal()).isEqualTo("1000.00");
        assertThat(view.totals().recurrenceTotal()).isEqualTo("3790.00");

        var expected = Map.ofEntries(
                Map.entry("2026-10", "310.00"), Map.entry("2026-11", "1340.00"), Map.entry("2026-12", "853.33"),
                Map.entry("2027-01", "1583.33"), Map.entry("2027-02", "350.00"), Map.entry("2027-03", "703.34"),
                Map.entry("2027-04", "370.00"), Map.entry("2027-05", "370.00"), Map.entry("2027-06", "370.00"),
                Map.entry("2027-07", "160.00"), Map.entry("2027-08", "160.00"), Map.entry("2027-09", "160.00"),
                Map.entry("2027-10", "170.00"));
        assertThat(view.months()).hasSize(13);
        view.months().forEach(m -> assertThat(m.totals().plannedTotal()).as(m.month()).isEqualTo(expected.get(m.month())));

        assertTotals(month(view, "2026-10"), 2, "310.00", "310.00", "0.00", 2, "310.00", 0, "0.00", 0, "0.00", 2,
                "310.00");
        assertTotals(month(view, "2026-11"), 3, "1340.00", "1340.00", "0.00", 3, "1340.00", 0, "0.00", 1, "100.00",
                2, "1240.00");
        assertTotals(month(view, "2026-12"), 4, "853.33", "433.33", "420.00", 2, "543.33", 2, "310.00", 0, "0.00",
                4, "853.33");
        assertTotals(month(view, "2027-01"), 5, "1583.33", "1373.33", "210.00", 2, "1233.33", 3, "350.00", 1,
                "880.00", 4, "683.33");
        assertTotals(month(view, "2027-02"), 3, "350.00", "140.00", "210.00", 0, "0.00", 3, "350.00", 0, "0.00", 3,
                "350.00");
        assertTotals(month(view, "2027-03"), 4, "703.34", "493.34", "210.00", 1, "333.34", 3, "370.00", 0, "0.00",
                4, "703.34");
        assertTotals(month(view, "2027-07"), 2, "160.00", "160.00", "0.00", 0, "0.00", 2, "160.00", 0, "0.00", 2,
                "160.00");
        assertTotals(month(view, "2027-10"), 3, "170.00", "170.00", "0.00", 1, "10.00", 2, "160.00", 0, "0.00", 3,
                "170.00");
        assertThat(month(view, "2026-11").oneOffTotal()).isEqualTo("1200.00");
        assertThat(month(view, "2026-11").recurrenceTotal()).isEqualTo("140.00");
        assertThat(month(view, "2026-12").installmentTotal()).isEqualTo("333.33");

        // Both roles see the same planning of the space.
        assertThat(planning(G, query(null, null, null, false, 0, 20)).totals()).isEqualTo(view.totals());
    }

    @Test
    void itemsOfAMonthIdentifyExpensesAndForecastsAndPrevailTheRealData() {
        matrix();
        var october = planning(A, query("2026-10", null, null, false, 0, 20));
        assertThat(october.content()).extracting(PlanningItemView::description, PlanningItemView::kind,
                PlanningItemView::amount, PlanningItemView::status, PlanningItemView::overdue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Internet", "EXPENSE", "100.00", "PENDING", true),
                        org.assertj.core.groups.Tuple.tuple("Luz", "EXPENSE", "210.00", "PENDING", false));
        assertThat(october.content().get(1).estimated()).isFalse();

        var december = planning(A, query("2026-12", null, null, false, 0, 20));
        assertThat(december.content()).extracting(PlanningItemView::date, PlanningItemView::description,
                PlanningItemView::kind, PlanningItemView::amount, PlanningItemView::estimated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(d(2026, 12, 2), "Luz", "EXPENSE", "210.00", true),
                        org.assertj.core.groups.Tuple.tuple(d(2026, 12, 5), "Internet", "FORECAST", "100.00", false),
                        org.assertj.core.groups.Tuple.tuple(d(2026, 12, 20), "Luz", "FORECAST", "210.00", true),
                        org.assertj.core.groups.Tuple.tuple(d(2026, 12, 31), "Sofá", "EXPENSE", "333.33", false));
        assertThat(december.content().get(3).installment().number()).isEqualTo(1);
        assertThat(december.content().get(3).installment().count()).isEqualTo(3);
        assertThat(december.content().get(1).categoryName()).isEqualTo("Casa e contas");
        assertThat(december.content()).noneMatch(i -> i.description().equals("Streaming"));

        var november = planning(A, query("2026-11", null, null, false, 0, 20));
        assertThat(november.content()).extracting(PlanningItemView::description)
                .containsExactly("Internet", "Seguro carro", "Streaming");
        var paid = november.content().getFirst();
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paidAmount()).isEqualTo("100.00");
        assertThat(paid.paymentDate()).isEqualTo(d(2026, 10, 15));
        assertThat(november.content().get(2).status()).isEqualTo("PENDING");
        assertThat(november.content().get(2).paidAmount()).isNull();

        // Pages of two items merge expenses and forecasts without skipping or repeating any.
        var pages = new ArrayList<PlanningItemView>();
        for (int page = 0; page < 3; page++) {
            var view = planning(A, query("2027-01", null, null, false, page, 2));
            assertThat(view.totalElements()).isEqualTo(5);
            assertThat(view.totalPages()).isEqualTo(3);
            assertThat(view.monthTotals().plannedTotal()).isEqualTo("1583.33");
            pages.addAll(view.content());
        }
        assertThat(pages).extracting(PlanningItemView::description, PlanningItemView::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Internet", "FORECAST"),
                org.assertj.core.groups.Tuple.tuple("Streaming", "FORECAST"),
                org.assertj.core.groups.Tuple.tuple("IPVA", "EXPENSE"),
                org.assertj.core.groups.Tuple.tuple("Luz", "FORECAST"),
                org.assertj.core.groups.Tuple.tuple("Sofá", "EXPENSE"));
        assertThat(planning(A, query("2027-01", null, null, false, 3, 2)).content()).isEmpty();

        var july = planning(A, query("2027-07", null, null, false, 0, 20));
        assertThat(july.content()).extracting(PlanningItemView::description).containsExactly("Internet", "Streaming");
        assertThat(july.content().getFirst().amount()).isEqualTo("120.00");
        var march = planning(A, query("2027-03", null, null, false, 0, 20));
        assertThat(march.content()).extracting(PlanningItemView::description, PlanningItemView::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Sofá", "333.34"),
                        org.assertj.core.groups.Tuple.tuple("Internet", "120.00"),
                        org.assertj.core.groups.Tuple.tuple("Streaming", "40.00"),
                        org.assertj.core.groups.Tuple.tuple("Luz", "210.00"));
    }

    @Test
    void filtersApplyToExpensesAndForecastsAlikeAndSpacesAreIsolated() {
        matrix();
        var casa = planning(A, query(null, null, housing, false, 0, 20));
        assertThat(casa.totals().count()).isEqualTo(14);
        assertThat(casa.totals().plannedTotal()).isEqualTo("2660.00");
        assertThat(casa.totals().forecastTotal()).isEqualTo("1260.00");

        var light = planning(A, query(null, "  LUZ ", null, false, 0, 20));
        assertThat(light.totals().count()).isEqualTo(9);
        assertThat(light.totals().plannedTotal()).isEqualTo("1890.00");
        assertThat(light.totals().estimatedTotal()).isEqualTo("1680.00");

        var withoutCategory = planning(A, query(null, null, null, true, 0, 20));
        assertThat(withoutCategory.totals().plannedTotal()).isEqualTo("4240.00");

        var other = planning(O, query("2026-11", null, null, false, 0, 20));
        assertThat(other.totals().plannedTotal()).isEqualTo("8900.00");
        assertThat(other.totals().forecastCount()).isEqualTo(12);
        assertThat(other.content()).extracting(PlanningItemView::description)
                .containsExactly("Aluguel alheio", "Outro espaço");
        var foreignCategory = tx.execute(s -> categories.create(O, "Outra categoria").id());
        assertThat(planning(A, query(null, null, foreignCategory, false, 0, 20)).totals().count()).isZero();
        assertThatThrownBy(() -> planning("nobody@example.com", query(null, null, null, false, 0, 20)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> planning(A, query("2027-11", null, null, false, 0, 20)))
                .isInstanceOf(ReportQueryValidationException.class).hasMessageContaining("10/2026 e 10/2027");
        assertThatThrownBy(() -> planning(A, query("2026-09", null, null, false, 0, 20)))
                .isInstanceOf(ReportQueryValidationException.class);
    }

    @Test
    void archivedCategoryAndDepartedResponsibleLeaveForecastsLikeTheGeneration() {
        var gym = tx.execute(s -> categories.create(A, "Academia").id());
        var fee = tx.execute(status -> recurrences.create(A, new CreateRecurrenceCommand("Mensalidade", "90.00",
                RecurrenceValueType.FIXED, RecurrenceFrequency.MONTHLY, d(2026, 11, 3), null, gym, GUEST,
                UUID.randomUUID())).recurrence().id());
        assertThat(planning(A, query(null, null, gym, false, 0, 20)).totals().count()).isEqualTo(12);

        jdbc.update("update expense_categories set archived_at = ? where id = ?", Timestamp.from(NOW), gym);
        jdbc.update("update space_memberships set active = false, ended_at = ?, ended_by_user_id = ?, "
                + "end_reason = 'ADMIN_REMOVAL' where user_id = ?", Timestamp.from(NOW), ADMIN, GUEST);

        assertThat(planning(A, query(null, null, gym, false, 0, 20)).totals().count()).isZero();
        var none = planning(A, query("2026-11", null, null, true, 0, 20));
        assertThat(none.totals().plannedTotal()).isEqualTo("1080.00");
        assertThat(none.content().getFirst().categoryName()).isNull();
        assertThat(none.content().getFirst().responsibleDisplayName()).isNull();
        // The generation records the same: no category and no responsible.
        var generated = anticipate(fee, d(2026, 11, 3));
        assertThat(current(generated).categoryId()).isNull();
        assertThat(current(generated).responsibleUserId()).isNull();
    }

    @Test
    void largerSetsThanAPageAreListedOnceInAStableOrderAndSummedWhole() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 130; i++)
            ids.add(pending("Compra " + i, "10.01", d(2026, 12, 1 + i % 3), null));
        recurrence("Condomínio", "650.00", RecurrenceValueType.FIXED, d(2026, 12, 2), null);
        var seen = new ArrayList<PlanningItemView>();
        for (int page = 0; page < 3; page++) {
            var view = planning(A, query("2026-12", null, null, false, page, 50));
            assertThat(view.monthTotals().count()).isEqualTo(131);
            assertThat(view.monthTotals().plannedTotal()).isEqualTo("1951.30");
            seen.addAll(view.content());
        }
        assertThat(seen).hasSize(131);
        assertThat(seen).extracting(PlanningItemView::expenseId).filteredOn(java.util.Objects::nonNull)
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(seen).extracting(PlanningItemView::date).isSorted();
        var forecast = seen.stream().filter(i -> i.kind().equals("FORECAST")).toList();
        assertThat(forecast).hasSize(1);
        // The forecast of 02/12 comes after every expense of 01/12 and 02/12 and before those of 03/12.
        assertThat(seen.indexOf(forecast.getFirst())).isEqualTo(87);
        assertThat(seen.stream().map(i -> new BigDecimal(i.amount())).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1951.30");
        var again = planning(A, query("2026-12", null, null, false, 1, 50)).content();
        assertThat(again).isEqualTo(seen.subList(50, 100));
    }

    @Test
    void horizonBoundariesFollowTheSpaceCalendarAcrossTheYear() {
        pending("Primeiro dia", "1.00", d(2026, 10, 1), null);
        pending("Último dia", "2.00", d(2027, 10, 31), null);
        pending("Depois", "4.00", d(2027, 11, 1), null);
        pending("Antes", "8.00", d(2026, 9, 30), null);
        var endOfMonth = recurrence("Dia 31", "31.00", RecurrenceValueType.FIXED, d(2026, 10, 31), null);
        var view = planning(A, query(null, null, null, false, 0, 20));
        // 1 + 2 + 13 forecasts of 31.00 (10/2026 to 10/2027) = 406.00; "Depois" and "Antes" are outside.
        assertThat(view.totals().plannedTotal()).isEqualTo("406.00");
        var february = planning(A, query("2027-02", null, null, false, 0, 20));
        assertThat(february.content().getFirst().date()).isEqualTo(d(2027, 2, 28));
        var january = planning(A, query("2027-01", null, null, false, 0, 20));
        assertThat(january.content().getFirst().date()).isEqualTo(d(2027, 1, 31));
        assertThat(endOfMonth).isNotNull();

        // 02:30 UTC on 1/11 is still 31/10 in São Paulo: the horizon does not move yet.
        var nearMidnight = new TransactionalPlanningUseCase(new PlanningService(new JdbcExpensePlanningQueries(jdbc),
                new RecurrenceForecastCatalog(RecurrenceTestFixtures.service(jdbc,
                        new AuthenticatedUserContextService(contextRepository()), new JdbcCategoryRepository(jdbc),
                        new JdbcFinancialMemberAccess(jdbc), Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"),
                                ZoneOffset.UTC), new JdbcRecurringExpenseMaterializer(jdbc, tx), null)),
                new AuthenticatedUserContextService(contextRepository()),
                Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"), ZoneOffset.UTC)),
                new TransactionTemplate(tx.getTransactionManager()));
        assertThat(nearMidnight.planning(A, query(null, null, null, false, 0, 20)).horizonStart()).isEqualTo("2026-10");
    }

    private static PlanningTotalsView month(PlanningView view, String month) {
        return view.months().stream().filter(m -> m.month().equals(month)).findFirst().orElseThrow().totals();
    }

    private static void assertTotals(PlanningTotalsView t, long count, String planned, String confirmed,
            String estimated, long materializedCount, String materialized, long forecastCount, String forecast,
            long paidCount, String paid, long openCount, String open) {
        assertThat(t.count()).isEqualTo(count);
        assertThat(t.plannedTotal()).isEqualTo(planned);
        assertThat(t.confirmedTotal()).isEqualTo(confirmed);
        assertThat(t.estimatedTotal()).isEqualTo(estimated);
        assertThat(t.materializedCount()).isEqualTo(materializedCount);
        assertThat(t.materializedTotal()).isEqualTo(materialized);
        assertThat(t.forecastCount()).isEqualTo(forecastCount);
        assertThat(t.forecastTotal()).isEqualTo(forecast);
        assertThat(t.paidCount()).isEqualTo(paidCount);
        assertThat(t.paidTotal()).isEqualTo(paid);
        assertThat(t.openCount()).isEqualTo(openCount);
        assertThat(t.openTotal()).isEqualTo(open);
    }

    private PlanningView planning(String email, PlanningQuery query) {
        return planning.planning(email, query);
    }

    private static PlanningQuery query(String month, String search, UUID category, boolean withoutCategory, int page,
            int size) {
        return new PlanningQuery(month, search, category, withoutCategory, null, false, page, size);
    }

    private List<Long> rowCounts() {
        return List.of(count("expense_entries"), count("recurrence_occurrences"), count("recurrence_generation_jobs"),
                count("expense_payment_events"));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID pending(String description, String amount, LocalDate due, UUID category) {
        return tx.execute(s -> expenses.create(A, new CreateOneOffExpenseCommand(description, amount,
                ExpenseStatus.PENDING, due, null, null, UUID.randomUUID(), null, null, null, category, null))
                .expense().id());
    }

    private ExpenseView current(UUID id) {
        return expenses.get(A, id);
    }

    private void settle(UUID id, String paid, LocalDate date) {
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(id, current(id).version(), paid, date, ADMIN,
                null, UUID.randomUUID())));
    }

    private void cancel(UUID id) {
        tx.execute(s -> expenses.cancel(A, new CancelExpenseCommand(id, current(id).version(), "Não será cobrada",
                UUID.randomUUID())));
    }

    private void correctDue(UUID id, LocalDate due) {
        var e = current(id);
        tx.execute(s -> expenses.correct(A, new CorrectExpenseCommand(id, e.version(), e.status(), e.description(),
                e.amount(), due, e.notes(), null, null, null, null, UUID.randomUUID(), e.categoryId(),
                e.responsibleUserId())));
    }

    private InstallmentPurchaseView purchase(String description, String total, int count, LocalDate first) {
        return tx.execute(s -> installments.create(A, new InstallmentPurchaseCommand(description, total, count, first,
                null, null, UUID.randomUUID())).purchase());
    }

    private UUID recurrence(String description, String amount, RecurrenceValueType type, LocalDate first,
            UUID category) {
        return tx.execute(s -> recurrences.create(A, new CreateRecurrenceCommand(description, amount, type,
                RecurrenceFrequency.MONTHLY, first, null, category, null, UUID.randomUUID())).recurrence().id());
    }

    private UUID anticipate(UUID recurrence, LocalDate due) {
        return tx.execute(s -> recurrences.anticipate(A, recurrence, due, UUID.randomUUID()).occurrence().expenseId());
    }

    private UUID occurrence(UUID recurrence, LocalDate scheduled) {
        return jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id = ? "
                + "and scheduled_due_date = ?", UUID.class, recurrence, scheduled);
    }

    private long version(UUID recurrence) {
        return recurrences.list(A).stream().filter(r -> r.id().equals(recurrence)).findFirst().orElseThrow().version();
    }

    private void change(UUID recurrence, LocalDate from, String amount, UUID category) {
        var command = new ChangeRecurrenceCommand(recurrence, version(recurrence), from, "Internet", amount,
                RecurrenceFrequency.MONTHLY, from.getDayOfMonth(), category, null, null, null);
        var impact = tx.execute(s -> recurrences.previewChange(A, command));
        tx.execute(s -> recurrences.change(A, new ChangeRecurrenceCommand(recurrence, command.version(), from,
                "Internet", amount, RecurrenceFrequency.MONTHLY, from.getDayOfMonth(), category, null,
                impact.impactToken(), UUID.randomUUID())));
    }

    private void close(UUID recurrence, LocalDate last) {
        var version = version(recurrence);
        var impact = tx.execute(s -> recurrences.previewClosure(A, new CloseRecurrenceCommand(recurrence, version,
                last, null, null, null)));
        tx.execute(s -> recurrences.close(A, new CloseRecurrenceCommand(recurrence, version, last,
                "Troca de fornecedor", impact.impactToken(), UUID.randomUUID())));
    }

    private static LocalDate d(int year, int month, int day) {
        return LocalDate.of(year, month, day);
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
}
