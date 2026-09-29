package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseExportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.ExportedExpense;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseExportQueries;
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
import com.malyah.accountmanager.installments.infrastructure.InstallmentTestFixtures;
import com.malyah.accountmanager.recurrences.application.CreateRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastCatalog;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;
import com.malyah.accountmanager.recurrences.infrastructure.RecurrenceTestFixtures;
import com.malyah.accountmanager.reporting.application.CsvFile;
import com.malyah.accountmanager.reporting.application.ExpenseExportQuery;
import com.malyah.accountmanager.reporting.application.ExportLimitExceededException;
import com.malyah.accountmanager.reporting.application.ExportService;
import com.malyah.accountmanager.reporting.application.ExportUseCase;
import com.malyah.accountmanager.reporting.application.ForecastExportQuery;

/**
 * H06.4 against real PostgreSQL: the CSV has exactly the rows and order of the expense list for the same filters,
 * the documented columns and format, the limit before writing, space isolation and one snapshot per file. Expected
 * values come from the matrix in docs/evidencias/H06.4.md.
 */
@Testcontainers
class ExportPostgresIT {
    // 12:00 in São Paulo on 15/10/2026.
    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    private static final UUID GUEST = UUID.fromString("a0000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final String A = "admin@example.com";
    private static final String G = "guest@example.com";
    private static final String O = "other@example.com";
    private static final String HEADER = "﻿\"Descrição\";\"Categoria\";\"Vencimento\";\"Valor da cobrança\";"
            + "\"Estimativa\";\"Situação\";\"Valor pago\";\"Data do pagamento\";\"Responsável\";\"Pagador\";"
            + "\"Origem\";\"Parcela\";\"ID do lançamento\"";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_export_test").withUsername("account_manager")
            .withPassword("test-only-password");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private Clock clock;
    private AuthenticatedUserContextService context;
    private ExpenseService expenses;
    private RecurrenceService recurrences;
    private InstallmentPurchaseService installments;
    private CategoryService categories;
    private RecurrenceForecastCatalog forecasts;
    private ExportUseCase exports;
    private UUID housing;

    @BeforeEach
    void reset() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(27);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Bia Convidada", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        context = new AuthenticatedUserContextService(contextRepository());
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
        forecasts = new RecurrenceForecastCatalog(RecurrenceTestFixtures.service(jdbc, context, categoryRepository,
                members, clock, new JdbcRecurringExpenseMaterializer(jdbc, tx), null));
        exports = exportsWith(new JdbcExpenseExportQueries(jdbc));
        housing = tx.execute(status -> categories.create(A, "Casa & contas").id());
    }

    private ExportUseCase exportsWith(ExpenseExportQueries queries) {
        return new TransactionalExportUseCase(new ExportService(queries, forecasts, context, clock),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    /** Matrix data of docs/evidencias/H06.4.md (X-rows), in their final state. */
    private Data matrix() {
        var rent = create("Aluguel; sala \"B\"", "1500.00", d(2026, 10, 5), housing, GUEST);          // overdue
        var water = create("Água\nconta nova", "80.10", d(2026, 10, 15), null, null);                  // due today
        var formula = create("=HYPERLINK(\"http://x\")", "0.01", d(2026, 10, 31), null, null);
        var big = create("-10% desconto", "99999999.99", d(2026, 10, 20), housing, null);
        settle(big, "99999999.99", d(2026, 10, 14), GUEST);
        var cancelled = create("Revisão", "300.00", d(2026, 10, 10), null, null);
        cancel(cancelled);
        var september = create("Setembro pago em outubro", "150.00", d(2026, 9, 30), null, null);
        settle(september, "155.00", d(2026, 10, 1), ADMIN);
        create("Início de novembro", "2.00", d(2026, 11, 1), null, null);
        var endOfMonth = create("Fim do mês", "1.00", d(2026, 10, 31), null, null);
        var tieA = create("Empate", "10.00", d(2026, 10, 12), null, null);
        var tieB = create("Empate", "10.00", d(2026, 10, 12), null, null);
        var sofa = tx.execute(s -> installments.create(A, new InstallmentPurchaseCommand("Sofá", "100.00", 3,
                d(2026, 10, 25), null, null, UUID.randomUUID())).purchase());
        var light = tx.execute(s -> recurrences.create(A, new CreateRecurrenceCommand("Luz", "180.00",
                RecurrenceValueType.VARIABLE_ESTIMATE, RecurrenceFrequency.MONTHLY, d(2026, 10, 20), null, housing,
                null, UUID.randomUUID())).recurrence().id());
        var lightOctober = tx.execute(s -> recurrences.anticipate(A, light, d(2026, 10, 20), UUID.randomUUID())
                .occurrence().expenseId());
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Outro espaço', 500.00, true, 'PENDING', date '2026-10-10', date '2026-10-10',
                    ?, ?, 0)
                """, UUID.randomUUID(), OTHER, OUTSIDER, Timestamp.from(NOW));
        return new Data(rent, water, formula, big, cancelled, september, endOfMonth, tieA, tieB,
                sofa.installments().getFirst().expenseId(), lightOctober, light);
    }

    private record Data(UUID rent, UUID water, UUID formula, UUID big, UUID cancelled, UUID september,
            UUID endOfMonth, UUID tieA, UUID tieB, UUID sofa1, UUID lightOctober, UUID lightRecurrence) { }

    @Test
    void theDefaultFileHasTheListRowsInTheListOrderWithExactContent() {
        var data = matrix();
        var before = rowCounts();

        var file = exports.expenses(A, query(null, null, null, ExpenseStatusFilter.ACTIVE));

        assertThat(rowCounts()).as("exporting writes nothing").isEqualTo(before);
        assertThat(file.fileName()).isEqualTo("despesas_vencimento_2026-10-01_a_2026-10-31.csv");
        assertThat(file.rows()).isEqualTo(9);
        var text = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(file.content()[0]).isEqualTo((byte) 0xEF);
        var records = records(text);
        assertThat(records.getFirst()).isEqualTo(HEADER);
        assertThat(ids(file)).containsExactlyElementsOf(listIds(A, query(null, null, null, ExpenseStatusFilter.ACTIVE),
                ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
        assertThat(ids(file)).containsExactlyInAnyOrder(data.rent(), data.tieA(), data.tieB(), data.water(),
                data.big(), data.lightOctober(), data.sofa1(), data.formula(), data.endOfMonth());
        // By due date; the two "Empate" rows share date, amount and creation instant, so the id breaks the tie.
        assertThat(ids(file).subList(0, 4)).containsExactly(data.rent(), idsOrdered(data.tieA(), data.tieB()).get(0),
                idsOrdered(data.tieA(), data.tieB()).get(1), data.water());
        assertThat(ids(file).subList(6, 7)).containsExactly(data.sofa1());
        assertThat(records.stream().skip(1).map(r -> LocalDate.parse(fields(r).get(2),
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))).toList()).isSorted();
        assertThat(records).contains(
                "\"Aluguel; sala \"\"B\"\"\";\"Casa & contas\";05/10/2026;1500,00;Não;Atrasada;;;\"Bia Convidada\";;"
                        + "Avulsa;;" + data.rent(),
                "\"Água\nconta nova\";;15/10/2026;80,10;Não;Pendente;;;;;Avulsa;;" + data.water(),
                "\"'=HYPERLINK(\"\"http://x\"\")\";;31/10/2026;0,01;Não;Pendente;;;;;Avulsa;;" + data.formula(),
                "\"'-10% desconto\";\"Casa & contas\";20/10/2026;99999999,99;Não;Paga;99999999,99;14/10/2026;;"
                        + "\"Bia Convidada\";Avulsa;;" + data.big(),
                "\"Sofá\";;25/10/2026;33,33;Não;Pendente;;;;;Parcela;1 de 3;" + data.sofa1(),
                "\"Luz\";\"Casa & contas\";20/10/2026;180,00;Sim;Pendente;;;;;Recorrência;;" + data.lightOctober());
        assertThat(text).doesNotContain("Revisão", "Setembro pago", "Início de novembro", "Outro espaço", "Previsão");
        // Read back as a spreadsheet would: every record has the 13 columns and the texts come back intact.
        assertThat(records).allSatisfy(r -> assertThat(fields(r)).hasSize(13));
        assertThat(records.stream().skip(1).map(r -> fields(r).get(0)).toList()).contains("Aluguel; sala \"B\"",
                "Água\nconta nova", "'=HYPERLINK(\"http://x\")", "'-10% desconto");
        // CRLF ends every record; the line break inside the quoted description is the only bare LF.
        assertThat(text.split("\r\n", -1)).hasSize(11);
        assertThat(text).endsWith("\r\n");
    }

    @Test
    void everyFilterStatusAndBasisSelectsExactlyWhatTheListSelects() {
        var data = matrix();
        record Case(ExpenseExportQuery query, int rows) { }
        var cases = List.of(
                new Case(query(null, null, null, ExpenseStatusFilter.ALL), 10),
                new Case(query(null, null, null, ExpenseStatusFilter.CANCELLED), 1),
                new Case(query(null, null, null, ExpenseStatusFilter.PENDING), 8),
                new Case(query(null, null, null, ExpenseStatusFilter.OVERDUE), 3),
                new Case(query(null, null, null, ExpenseStatusFilter.PAID), 1),
                new Case(new ExpenseExportQuery(" ÁGUA ", null, null, null, null, false, null, false, null, null, null,
                        null), 1),
                new Case(new ExpenseExportQuery(null, null, null, null, housing, false, null, false, null, null, null,
                        null), 3),
                new Case(new ExpenseExportQuery(null, null, null, null, null, true, null, false, null, null, null,
                        null), 6),
                new Case(new ExpenseExportQuery(null, null, null, null, null, false, GUEST, false, null, null, null,
                        null), 1),
                new Case(new ExpenseExportQuery(null, null, null, null, null, false, null, true, null, null, null,
                        null), 8),
                new Case(new ExpenseExportQuery(null, null, null, null, null, false, null, false, GUEST, null, null,
                        null), 1),
                new Case(new ExpenseExportQuery(null, d(2026, 10, 1), d(2026, 10, 31), ExpenseDateBasis.PAYMENT_DATE,
                        null, false, null, false, null, null, null, null), 2),
                new Case(new ExpenseExportQuery(null, d(2026, 9, 30), d(2026, 11, 1), null, null, false, null, false,
                        null, ExpenseStatusFilter.ALL, null, null), 12),
                new Case(new ExpenseExportQuery(null, d(2026, 10, 31), null, null, null, false, null, false, null, null,
                        null, null), 5),
                new Case(new ExpenseExportQuery(null, null, d(2026, 10, 1), null, null, false, null, false, null, null,
                        null, null), 1));
        for (var c : cases) {
            var file = exports.expenses(A, c.query());
            assertThat(file.rows()).as(c.query().toString()).isEqualTo(c.rows());
            for (var sort : ExpenseSort.values())
                for (var direction : SortDirection.values()) {
                    var sorted = withOrder(c.query(), sort, direction);
                    assertThat(ids(exports.expenses(A, sorted))).as(sorted.toString())
                            .containsExactlyElementsOf(listIds(A, sorted, sort, direction));
                }
        }
        var payment = exports.expenses(A, new ExpenseExportQuery(null, d(2026, 10, 1), d(2026, 10, 31),
                ExpenseDateBasis.PAYMENT_DATE, null, false, null, false, null, null, null, null));
        assertThat(payment.fileName()).isEqualTo("despesas_pagamento_2026-10-01_a_2026-10-31.csv");
        assertThat(records(new String(payment.content(), StandardCharsets.UTF_8))).contains(
                "\"Setembro pago em outubro\";;30/09/2026;150,00;Não;Paga;155,00;01/10/2026;;\"Admin\";Avulsa;;"
                        + data.september());
        var all = new String(exports.expenses(A, query(null, null, null, ExpenseStatusFilter.ALL)).content(),
                StandardCharsets.UTF_8);
        assertThat(records(all)).contains("\"Revisão\";;10/10/2026;300,00;Não;Cancelada;;;;;Avulsa;;"
                + data.cancelled());
        // The guest exports the same space as the administrator.
        assertThat(exports.expenses(G, query(null, null, null, ExpenseStatusFilter.ALL)).content())
                .isEqualTo(all.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void moreRowsThanAListPageAreAllExportedOnceInTheListOrder() {
        for (int i = 0; i < 130; i++) create("Compra " + (i % 7), (i % 5 + 1) + ".01", d(2026, 10, 1 + i % 3), null,
                null);
        for (var sort : ExpenseSort.values()) {
            var sorted = withOrder(query(null, null, null, null), sort, SortDirection.DESC);
            var file = exports.expenses(A, sorted);
            assertThat(file.rows()).isEqualTo(130);
            var listed = listIds(A, sorted, sort, SortDirection.DESC);
            assertThat(listed).hasSize(130).doesNotHaveDuplicates();
            assertThat(ids(file)).containsExactlyElementsOf(listed);
        }
    }

    @Test
    void anEmptySelectionGivesTheHeaderOnly() {
        matrix();
        var file = exports.expenses(A, new ExpenseExportQuery(null, d(2027, 1, 1), d(2027, 1, 31), null, null, false,
                null, false, null, null, null, null));
        assertThat(file.rows()).isZero();
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo(HEADER + "\r\n");
    }

    @Test
    void spacesAreIsolatedAndOnlyActiveMembersExport() {
        var data = matrix();
        var foreign = exports.expenses(O, query(null, null, null, ExpenseStatusFilter.ALL));
        assertThat(foreign.rows()).isEqualTo(1);
        assertThat(new String(foreign.content(), StandardCharsets.UTF_8)).contains("Outro espaço")
                .doesNotContain(data.rent().toString());
        var foreignCategory = tx.execute(s -> categories.create(O, "Outra").id());
        assertThat(exports.expenses(A, new ExpenseExportQuery(null, null, null, null, foreignCategory, false, null,
                false, null, ExpenseStatusFilter.ALL, null, null)).rows()).isZero();
        assertThat(exports.expenses(A, new ExpenseExportQuery(null, null, null, null, null, false, OUTSIDER, false,
                null, ExpenseStatusFilter.ALL, null, null)).rows()).isZero();
        assertThatThrownBy(() -> exports.expenses("nobody@example.com", query(null, null, null, null)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        jdbc.update("update space_memberships set active = false, ended_at = ?, ended_by_user_id = ?, "
                + "end_reason = 'ADMIN_REMOVAL' where user_id = ?", Timestamp.from(NOW), ADMIN, GUEST);
        assertThatThrownBy(() -> exports.expenses(G, query(null, null, null, null)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> exports.forecasts(G, new ForecastExportQuery(null, null, false, null, false)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    @Test
    void forecastsAreASeparateFileAndNeverMixWithExpenses() {
        var data = matrix();
        var file = exports.forecasts(A, new ForecastExportQuery(null, null, false, null, false));
        // Luz from 11/2026 to 10/2027 (October is already an expense): 12 forecasts of 180,00 to be confirmed.
        assertThat(file.fileName()).isEqualTo("previsoes_2026-10_a_2027-10.csv");
        assertThat(file.rows()).isEqualTo(12);
        var records = records(new String(file.content(), StandardCharsets.UTF_8));
        assertThat(records.getFirst()).isEqualTo("﻿\"Descrição\";\"Categoria\";\"Vencimento previsto\";"
                + "\"Valor previsto\";\"Estimativa\";\"Responsável\";\"Tipo\";\"ID da recorrência\"");
        assertThat(records.get(1)).isEqualTo("\"Luz\";\"Casa & contas\";20/11/2026;180,00;Sim;;Previsão de recorrência;"
                + data.lightRecurrence());
        assertThat(records.getLast()).startsWith("\"Luz\";\"Casa & contas\";20/10/2027;");
        assertThat(String.join("\n", records)).doesNotContain(data.lightOctober().toString(), "20/10/2026");
        assertThat(exports.forecasts(A, new ForecastExportQuery(null, null, true, null, false)).rows()).isZero();
        assertThat(exports.forecasts(A, new ForecastExportQuery("luz", housing, false, null, false)).rows())
                .isEqualTo(12);
        assertThat(exports.forecasts(O, new ForecastExportQuery(null, null, false, null, false)).rows()).isZero();
    }

    @Test
    void tenThousandRowsAreExportedWellWithinSixtySecondsAndOneMoreIsRefused() {
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, created_by_user_id, created_at, version)
                select gen_random_uuid(), ?, 'ONE_OFF', 'Carga ' || n || ' – açúcar "fino"', n * 0.01, true, 'PENDING',
                       date '2026-10-01' + (n % 31), date '2026-10-01' + (n % 31), ?, ?, 0
                  from generate_series(1, 10000) n
                """, SPACE, ADMIN, Timestamp.from(NOW));
        var started = System.nanoTime();
        var file = exports.expenses(A, query(null, null, null, null));
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(file.rows()).isEqualTo(10_000);
        var records = records(new String(file.content(), StandardCharsets.UTF_8));
        assertThat(records).hasSize(10_001);
        var total = records.stream().skip(1).map(r -> new java.math.BigDecimal(r.split(";")[3].replace(',', '.')))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        // 0.01 × (10000 × 10001 / 2): no cent lost between the database and the file.
        assertThat(total).isEqualByComparingTo("500050.00");
        System.out.println("H06.4 export of 10,000 rows: " + elapsedMillis + " ms, " + file.content().length + " bytes");
        assertThat(elapsedMillis).isLessThan(60_000);
        assertThat(file.content().length).isLessThan(3 * 1024 * 1024);

        create("Uma a mais", "1.00", d(2026, 10, 2), null, null);
        assertThatThrownBy(() -> exports.expenses(A, query(null, null, null, null)))
                .isInstanceOf(ExportLimitExceededException.class)
                .hasMessageStartingWith("A seleção tem 10.001 registros e a exportação aceita até 10.000.");
        assertThat(exports.expenses(A, new ExpenseExportQuery("uma a mais", null, null, null, null, false, null, false,
                null, null, null, null)).rows()).isEqualTo(1);
    }

    @Test
    void aChangeCommittedDuringTheExportIsNotHalfIncluded() {
        var data = matrix();
        var concurrent = new ExpenseExportQueries() {
            private final JdbcExpenseExportQueries real = new JdbcExpenseExportQueries(jdbc);

            @Override
            public long count(UUID spaceId, ExpenseSelection selection) {
                var counted = real.count(spaceId, selection);
                // Another member records and cancels expenses after the count and before the rows are read.
                var other = new Thread(() -> {
                    create("Chegou depois", "5.00", d(2026, 10, 3), null, null);
                    cancel(data.endOfMonth());
                });
                other.start();
                try {
                    other.join();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return counted;
            }

            @Override
            public void export(UUID spaceId, ExpenseSelection selection, ExpenseSort sort, SortDirection direction,
                    int limit, Consumer<ExportedExpense> sink) {
                real.export(spaceId, selection, sort, direction, limit, sink);
            }
        };

        var file = exportsWith(concurrent).expenses(A, query(null, null, null, null));

        assertThat(file.rows()).isEqualTo(9);
        var text = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("Chegou depois").contains("\"Fim do mês\";;31/10/2026;1,00;Não;Pendente");
        // The next export sees the committed state.
        var next = new String(exports.expenses(A, query(null, null, null, null)).content(), StandardCharsets.UTF_8);
        assertThat(next).contains("Chegou depois").doesNotContain("Fim do mês");
    }

    private static ExpenseExportQuery query(String search, LocalDate from, LocalDate to, ExpenseStatusFilter status) {
        return new ExpenseExportQuery(search, from, to, null, null, false, null, false, null, status, null, null);
    }

    private static ExpenseExportQuery withOrder(ExpenseExportQuery q, ExpenseSort sort, SortDirection direction) {
        return new ExpenseExportQuery(q.search(), q.dateFrom(), q.dateTo(), q.dateBasis(), q.categoryId(),
                q.withoutCategory(), q.responsibleUserId(), q.withoutResponsible(), q.payerUserId(), q.status(), sort,
                direction);
    }

    /** Every page of GET /expenses for the same filters, the reference population and order of the CSV. */
    private List<UUID> listIds(String email, ExpenseExportQuery q, ExpenseSort sort, SortDirection direction) {
        var ids = new ArrayList<UUID>();
        for (int page = 0; ; page++) {
            var result = expenses.list(email, new ExpenseListQuery(page, 100, sort, direction, q.search(), q.dateFrom(),
                    q.dateTo(), q.dateBasis(), q.categoryId(), q.withoutCategory(), q.responsibleUserId(),
                    q.withoutResponsible(), q.payerUserId(), q.status(), null));
            result.content().stream().map(ExpenseView::id).forEach(ids::add);
            if (page + 1 >= result.totalPages()) return ids;
        }
    }

    /** CSV records (not lines): a record ends at a CRLF outside quotes; the BOM stays on the header. */
    private static List<String> records(String text) {
        var records = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            if (!quoted && c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                records.add(current.toString());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
            }
        }
        assertThat(current).as("the file ends with CRLF").isEmpty();
        return records;
    }

    /** RFC 4180 fields of one record: separators inside quotes belong to the field, doubled quotes are one. */
    private static List<String> fields(String record) {
        var fields = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < record.length(); i++) {
            var c = record.charAt(i);
            if (c == '"' && quoted && i + 1 < record.length() && record.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static List<UUID> ids(CsvFile file) {
        return records(new String(file.content(), StandardCharsets.UTF_8)).stream().skip(1)
                .map(r -> UUID.fromString(r.substring(r.lastIndexOf(';') + 1))).toList();
    }

    /** PostgreSQL orders uuid as unsigned bytes, which is the order of the hex text, not of UUID.compareTo. */
    private static List<UUID> idsOrdered(UUID... ids) {
        return Arrays.stream(ids).sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }

    private List<Long> rowCounts() {
        return List.of(count("expense_entries"), count("recurrence_occurrences"), count("expense_payment_events"),
                count("expense_correction_events"), count("recurrence_generation_jobs"));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID create(String description, String amount, LocalDate due, UUID category, UUID responsible) {
        return tx.execute(s -> expenses.create(A, new CreateOneOffExpenseCommand(description, amount,
                ExpenseStatus.PENDING, due, null, null, UUID.randomUUID(), null, null, null, category, responsible))
                .expense().id());
    }

    private void settle(UUID id, String paid, LocalDate date, UUID payer) {
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(id, expenses.get(A, id).version(), paid, date,
                payer, null, UUID.randomUUID())));
    }

    private void cancel(UUID id) {
        tx.execute(s -> expenses.cancel(A, new CancelExpenseCommand(id, expenses.get(A, id).version(),
                "Não será cobrada", UUID.randomUUID())));
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
