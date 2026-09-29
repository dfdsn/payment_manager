package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.malyah.accountmanager.expenses.application.CancelExpenseCommand;
import com.malyah.accountmanager.expenses.application.CategoryService;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.ReversePaymentCommand;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReportQueries;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentExpenses;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
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
import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.CloseMonthResult;
import com.malyah.accountmanager.reporting.application.MonthClosingService;
import com.malyah.accountmanager.reporting.application.MonthClosingUseCase;
import com.malyah.accountmanager.reporting.application.MonthClosingView;
import com.malyah.accountmanager.reporting.application.ReportingService;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;
import com.malyah.accountmanager.reporting.application.port.MonthClosingRepository;

/**
 * Shared real-PostgreSQL fixture of the E07 matrices (docs/evidencias/H07.x.md): the space A of Admin and
 * Convidado, the space O of Outro, and expenses created through their own use cases. One container serves every
 * closing IT; each test starts from a clean, fully migrated schema.
 */
abstract class MonthClosingTestSupport {
    // 12:00 in São Paulo on 15/10/2026: entries due on 14/10 or before are overdue, due today are not.
    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    static final UUID SPACE = UUID.fromString("80000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("90000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("80000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("80000000-0000-0000-0000-000000000003");
    static final UUID OUTSIDER = UUID.fromString("90000000-0000-0000-0000-000000000002");
    static final String A = "admin@example.com";
    static final String G = "guest@example.com";
    static final String O = "other@example.com";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_closing_test").withUsername("account_manager")
            .withPassword("test-only-password");

    static {
        POSTGRES.start();
    }

    DataSource dataSource;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AuthenticatedUserContextService context;
    JdbcFinancialMemberAccess members;
    ExpenseService expenses;
    RecurrenceService recurrences;
    InstallmentPurchaseService installments;
    CategoryService categories;
    ReportingUseCase reports;
    MonthClosingUseCase closings;
    UUID housing;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(expectedMigrations());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        context = new AuthenticatedUserContextService(contextRepository());
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        members = new JdbcFinancialMemberAccess(jdbc);
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
        closings = closings(new JdbcMonthClosingRepository(jdbc), new JdbcExpenseReportQueries(jdbc), clock);
        housing = tx.execute(status -> categories.create(A, "Casa e contas").id());
    }

    int expectedMigrations() {
        return 26;
    }

    MonthClosingUseCase closings(MonthClosingRepository repository, ExpenseReportQueries queries, Clock clock) {
        return new TransactionalMonthClosingUseCase(new MonthClosingService(repository, queries, context, members,
                clock, UUID::randomUUID), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    /** Ids of the October and September entries of the matrix. */
    static final class Dataset {
        UUID s1, s2, s3, s4, o1, o2, o3, o4, o5, o6, o7, o8, o9, foreign;
        InstallmentPurchaseView purchase;
    }

    /** September and October of the H07.1 matrix, in their final state. */
    Dataset matrix() {
        var data = new Dataset();
        data.s1 = pending("Condomínio set", "800.00", LocalDate.of(2026, 9, 20), null);
        data.s2 = pending("Água set", "150.00", LocalDate.of(2026, 9, 25), null);
        settle(data.s2, "155.00", LocalDate.of(2026, 10, 2), ADMIN);
        data.s3 = pending("Mercado set", "200.00", LocalDate.of(2026, 9, 28), null);
        cancel(data.s3);
        data.s4 = create(A, new CreateOneOffExpenseCommand("Farmácia set", "90.00", ExpenseStatus.PAID, null,
                LocalDate.of(2026, 9, 15), null, UUID.randomUUID()));

        data.o1 = pending("Aluguel", "1500.00", LocalDate.of(2026, 10, 10), housing);
        data.o2 = pending("Internet", "100.00", LocalDate.of(2026, 10, 15), null);
        var light = recurrence("Luz", "180.00", RecurrenceValueType.VARIABLE_ESTIMATE, LocalDate.of(2026, 10, 20),
                null);
        data.o3 = anticipate(light, LocalDate.of(2026, 10, 20));
        data.o4 = pending("Telefone", "120.00", LocalDate.of(2026, 10, 5), housing);
        settle(data.o4, "110.00", LocalDate.of(2026, 10, 6), GUEST);
        data.purchase = tx.execute(status -> installments.create(A, new InstallmentPurchaseCommand("Notebook",
                "1000.00", 3, LocalDate.of(2026, 10, 12), null, null, UUID.randomUUID())).purchase());
        data.o5 = data.purchase.installments().getFirst().expenseId();
        settle(data.o5, "333.33", LocalDate.of(2026, 10, 12), ADMIN);
        data.o6 = pending("Seguro", "250.00", LocalDate.of(2026, 10, 18), null);
        cancel(data.o6);
        data.o7 = pending("Escola", "400.00", LocalDate.of(2026, 10, 3), null);
        settle(data.o7, "400.00", LocalDate.of(2026, 10, 4), ADMIN);
        reverse(data.o7);
        settle(data.o7, "420.00", LocalDate.of(2026, 10, 14), ADMIN);
        data.o8 = pending("Academia", "99.00", LocalDate.of(2026, 10, 1), null);
        settle(data.o8, "99.00", LocalDate.of(2026, 9, 30), ADMIN);
        var gas = recurrence("Gás", "60.00", RecurrenceValueType.FIXED, LocalDate.of(2026, 10, 8), housing);
        data.o9 = anticipate(gas, LocalDate.of(2026, 10, 8));
        data.foreign = create(O, new CreateOneOffExpenseCommand("Outro espaço", "500.00", ExpenseStatus.PENDING,
                LocalDate.of(2026, 10, 10), null, null, UUID.randomUUID()));
        return data;
    }

    CloseMonthResult close(String email, String month, boolean acknowledgePending) {
        return close(email, month, acknowledgePending, UUID.randomUUID());
    }

    CloseMonthResult close(String email, String month, boolean acknowledgePending, UUID key) {
        return closings.close(email, new CloseMonthCommand(month, acknowledgePending, key));
    }

    MonthClosingView view(String email, String month) {
        return closings.view(email, month);
    }

    UUID pending(String description, String amount, LocalDate due, UUID category) {
        return create(A, new CreateOneOffExpenseCommand(description, amount, ExpenseStatus.PENDING, due, null, null,
                UUID.randomUUID(), null, null, null, category, null));
    }

    UUID create(String email, CreateOneOffExpenseCommand command) {
        return tx.execute(status -> expenses.create(email, command).expense().id());
    }

    ExpenseView current(UUID id) {
        return expenses.get(A, id);
    }

    void settle(UUID id, String paid, LocalDate date, UUID payer) {
        tx.execute(status -> expenses.settle(A, new SettleExpenseCommand(id, current(id).version(), paid, date, payer,
                null, UUID.randomUUID())));
    }

    void reverse(UUID id) {
        tx.execute(status -> expenses.reversePayment(G, new ReversePaymentCommand(id, current(id).version(),
                "Quitação registrada por engano", UUID.randomUUID())));
    }

    void cancel(UUID id) {
        tx.execute(status -> expenses.cancel(A, new CancelExpenseCommand(id, current(id).version(), "Não será cobrada",
                UUID.randomUUID())));
    }

    /** Corrects only the given fields of a pending entry, keeping category and responsible. */
    void correctPending(UUID id, String description, String amount, LocalDate due, UUID category) {
        var expense = current(id);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(id, expense.version(), expense.status(),
                description, amount, due, expense.notes(), null, null, null, null, UUID.randomUUID(), category,
                expense.responsibleUserId())));
    }

    /** Corrects a paid entry, keeping what is not given. */
    void correctPaid(UUID id, String description, String paid, LocalDate paymentDate, UUID category) {
        var expense = current(id);
        tx.execute(status -> expenses.correct(A, new CorrectExpenseCommand(id, expense.version(), ExpenseStatus.PAID,
                description, expense.amount(), expense.dueDate(), expense.notes(), paid, paymentDate,
                expense.paidByUserId(), null, UUID.randomUUID(), category, expense.responsibleUserId())));
    }

    UUID recurrence(String description, String amount, RecurrenceValueType type, LocalDate first, UUID category) {
        return tx.execute(status -> recurrences.create(A, new CreateRecurrenceCommand(description, amount, type,
                RecurrenceFrequency.MONTHLY, first, null, category, null, UUID.randomUUID())).recurrence().id());
    }

    UUID anticipate(UUID recurrence, LocalDate due) {
        return tx.execute(status -> recurrences.anticipate(A, recurrence, due, UUID.randomUUID()).occurrence()
                .expenseId());
    }

    /** The concurrent settlement waits for the space lock held by the closing: financial writes are serialized. */
    void waitUntilBlockedOnTheSpaceLock() {
        // A connection outside the closing transaction: activity statistics are frozen inside a transaction.
        var probe = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = probe.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("The concurrent settlement did not wait for the closing.");
    }

    /** Expense queries that run {@code onFirstRead} once, right after the first read of the entries of a month. */
    ExpenseReportQueries interceptingEntries(Runnable onFirstRead) {
        var delegate = new JdbcExpenseReportQueries(jdbc);
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        return new ExpenseReportQueries() {
            @Override
            public List<com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket> totals(UUID spaceId,
                    com.malyah.accountmanager.expenses.application.ExpenseSelection selection) {
                return delegate.totals(spaceId, selection);
            }

            @Override
            public com.malyah.accountmanager.expenses.application.PaymentRecordPage payments(UUID spaceId,
                    com.malyah.accountmanager.expenses.application.ExpenseSelection selection, int page, int size,
                    com.malyah.accountmanager.expenses.application.PaymentSort sort,
                    com.malyah.accountmanager.expenses.application.SortDirection direction) {
                return delegate.payments(spaceId, selection, page, size, sort, direction);
            }

            @Override
            public List<com.malyah.accountmanager.expenses.application.ReportedExpense> entries(UUID spaceId,
                    com.malyah.accountmanager.expenses.application.ExpenseSelection selection) {
                var rows = delegate.entries(spaceId, selection);
                if (done.compareAndSet(false, true)) onFirstRead.run();
                return rows;
            }
        };
    }

    /** Every closing row count, to prove nothing partial is left behind. */
    Map<String, Integer> closingRows() {
        return Map.of("month_closings", count("month_closings"),
                "month_closing_versions", count("month_closing_versions"),
                "month_closing_categories", count("month_closing_categories"),
                "month_closing_lines", count("month_closing_lines"),
                "month_closing_events", count("month_closing_events"),
                "month_closing_requests", count("month_closing_requests"));
    }

    int count(String table) {
        Integer value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    /** Id, version, situation, values and dates of every expense, to prove a closing changes none of them. */
    List<Map<String, Object>> expenseState() {
        return jdbc.queryForList("""
                select id, version, status, charge_amount, charge_confirmed, paid_amount, due_date, payment_date,
                       reference_date, category_id, description
                  from expense_entries order by id
                """);
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
