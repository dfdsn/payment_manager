package com.malyah.accountmanager.installments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.malyah.accountmanager.expenses.application.*;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.domain.ExpenseValidationException;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentExpenses;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.installments.application.*;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

/** H05.1 on real PostgreSQL: purchase, installment entries, idempotency, concurrency, references and rollback. */
@Testcontainers
class InstallmentPurchasePostgresIT {
    private static final Instant NOW = Instant.parse("2026-09-28T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID GUEST = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String A = "admin@example.com";
    private static final String G = "guest@example.com";
    private static final String O = "other@example.com";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_installment_test").withUsername("account_manager")
            .withPassword("test-only-password");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InstallmentPurchaseUseCase purchases;
    private ExpenseService expenses;

    @BeforeEach
    void reset() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(26);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        insertSpace(SPACE, "Casa"); insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        var context = new AuthenticatedUserContextService(contextRepository());
        var members = new JdbcFinancialMemberAccess(jdbc);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        expenses = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID, clock, members);
        var service = new InstallmentPurchaseService(new JdbcInstallmentPurchaseRepository(jdbc),
                new JdbcInstallmentExpenses(jdbc, UUID::randomUUID), context, new JdbcCategoryRepository(jdbc), members,
                clock, UUID::randomUUID);
        purchases = new TransactionalInstallmentPurchaseUseCase(service, tx);
    }

    @Test
    void createsThePurchaseAndItsNumberedInstallmentsWithTheRemainderOnTheLastAndNoHeaderExpense() {
        var category = insertCategory(SPACE, "Casa");
        var result = purchases.create(G, new InstallmentPurchaseCommand("Sofá", "100.00", 3, LocalDate.of(2027, 1, 31),
                category, ADMIN, UUID.randomUUID()));

        var purchase = result.purchase();
        assertThat(result.replayed()).isFalse();
        assertThat(purchase.totalAmount()).isEqualTo("100.00");
        assertThat(purchase.installmentsSum()).isEqualTo("100.00");
        assertThat(purchase.categoryName()).isEqualTo("Casa");
        assertThat(purchase.responsibleDisplayName()).isEqualTo("Admin");
        assertThat(purchase.createdByDisplayName()).isEqualTo("Convidado");
        assertThat(purchase.lastDueDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(purchase.installments()).extracting(InstallmentView::number, InstallmentView::amount, InstallmentView::dueDate)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "33.33", LocalDate.of(2027, 1, 31)),
                        org.assertj.core.groups.Tuple.tuple(2, "33.33", LocalDate.of(2027, 2, 28)),
                        org.assertj.core.groups.Tuple.tuple(3, "33.34", LocalDate.of(2027, 3, 31)));

        // Only the installments are expenses: the purchase header never becomes another entry.
        assertThat(count("select count(*) from expense_entries")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(charge_amount) from expense_entries where installment_purchase_id=?",
                BigDecimal.class, purchase.id())).isEqualTo(new BigDecimal("100.00"));
        assertThat(jdbc.queryForObject("select total_amount from installment_purchases where id=?", BigDecimal.class,
                purchase.id())).isEqualTo(new BigDecimal("100.00"));
        assertThat(jdbc.queryForList("""
                select origin||'|'||installment_number||'/'||installment_count||'|'||status||'|'||charge_confirmed||'|'||
                       description||'|'||category_id||'|'||responsible_user_id||'|'||created_by_user_id||'|'||version
                  from expense_entries where installment_purchase_id=? order by installment_number
                """, String.class, purchase.id())).containsExactly(
                "INSTALLMENT|1/3|PENDING|true|Sofá|" + category + "|" + ADMIN + "|" + GUEST + "|0",
                "INSTALLMENT|2/3|PENDING|true|Sofá|" + category + "|" + ADMIN + "|" + GUEST + "|0",
                "INSTALLMENT|3/3|PENDING|true|Sofá|" + category + "|" + ADMIN + "|" + GUEST + "|0");
        assertThat(count("select count(*) from installment_purchase_events where event_type='PURCHASE_CREATED'")).isOne();
        assertThat(count("select count(*) from installment_purchase_requests where completed_at is not null")).isOne();

        // The existing list shows each installment in its own month, identified as n/N.
        var february = list(A, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));
        assertThat(february).singleElement().satisfies(e -> {
            assertThat(e.origin()).isEqualTo("INSTALLMENT");
            assertThat(e.installment()).isEqualTo(new InstallmentLink(purchase.id(), 2, 3));
            assertThat(e.amount()).isEqualTo("33.33");
            assertThat(e.chargeConfirmed()).isTrue();
        });
        assertThat(list(A, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))).extracting(ExpenseView::amount)
                .containsExactlyInAnyOrder("33.33", "33.33", "33.34");
        assertThat(list(O, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))).isEmpty();
    }

    @Test
    void theSameRequestIsReplayedAndAReusedKeyWithOtherDataIsRejectedWithoutWriting() {
        var key = UUID.randomUUID();
        var command = new InstallmentPurchaseCommand("Geladeira", "1000.00", 7, LocalDate.of(2026, 10, 10), null, null, key);
        var first = purchases.create(A, command);
        var replay = purchases.create(A, command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.purchase()).usingRecursiveComparison().isEqualTo(first.purchase());
        assertThat(first.purchase().installments()).extracting(InstallmentView::amount)
                .containsExactly("142.85", "142.85", "142.85", "142.85", "142.85", "142.85", "142.90");
        assertThatThrownBy(() -> purchases.create(A, new InstallmentPurchaseCommand("Geladeira", "1000.00", 8,
                LocalDate.of(2026, 10, 10), null, null, key))).isInstanceOf(InstallmentIdempotencyConflictException.class);
        // The key belongs to its author: the other member gets a new purchase with the same key.
        assertThat(purchases.create(G, command).replayed()).isFalse();
        assertThat(count("select count(*) from installment_purchases")).isEqualTo(2);
        assertThat(count("select count(*) from expense_entries")).isEqualTo(14);
        assertThat(count("select count(*) from installment_purchase_events")).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOnePurchase() throws Exception {
        var key = UUID.randomUUID();
        var command = new InstallmentPurchaseCommand("Notebook", "4999.99", 10, LocalDate.of(2026, 11, 5), null, null, key);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<InstallmentPurchaseCreationResult>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> { start.await(); return purchases.create(A, command); }));
            start.countDown();
            var results = new java.util.ArrayList<InstallmentPurchaseCreationResult>();
            for (var task : tasks) results.add(task.get());
            assertThat(results).extracting(r -> r.purchase().id()).containsOnly(results.getFirst().purchase().id());
            assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        }
        assertThat(count("select count(*) from installment_purchases")).isOne();
        assertThat(count("select count(*) from expense_entries")).isEqualTo(10);
        assertThat(count("select count(*) from installment_purchase_events")).isOne();
    }

    @Test
    void ineligibleReferencesInactiveAuthorsAndForeignSpacesAreRejectedWithoutPartialPersistence() {
        var archived = insertCategory(SPACE, "Antiga");
        jdbc.update("update expense_categories set archived_at=? where id=?", Timestamp.from(NOW), archived);
        var foreign = insertCategory(OTHER, "Alheia");
        assertThatThrownBy(() -> create(A, "10.00", 2, archived, null)).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(() -> create(A, "10.00", 2, foreign, null)).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(() -> create(A, "10.00", 2, null, OUTSIDER)).isInstanceOf(InstallmentValidationException.class)
                .extracting("field").isEqualTo("responsibleUserId");
        assertThatThrownBy(() -> purchases.preview(A, new InstallmentPurchaseCommand("Compra", "10.00", 2,
                LocalDate.of(2026, 10, 1), archived, null, null))).isInstanceOf(CategoryConflictException.class);

        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW), ADMIN, GUEST);
        assertThatThrownBy(() -> create(A, "10.00", 2, null, GUEST)).isInstanceOf(InstallmentValidationException.class);
        assertThatThrownBy(() -> create(G, "10.00", 2, null, null)).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> create(A, "0.01", 2, null, null)).isInstanceOf(InstallmentValidationException.class);
        assertThatThrownBy(() -> create(A, "10.00", 361, null, null)).isInstanceOf(InstallmentValidationException.class);

        assertThat(count("select count(*) from installment_purchases")).isZero();
        assertThat(count("select count(*) from installment_purchase_requests")).isZero();
        assertThat(count("select count(*) from expense_entries")).isZero();
        // Another space can use its own category, and never sees this space's purchases.
        assertThat(create(O, "10.00", 2, foreign, null).purchase().categoryName()).isEqualTo("Alheia");
        assertThat(list(A, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))).isEmpty();
    }

    @Test
    void aFailureWhileCreatingInstallmentsRollsBackEverythingAndTheSameKeyCanBeRetried() {
        jdbc.execute("alter table expense_entries add constraint force_failure check (installment_number is distinct from 3)");
        var key = UUID.randomUUID();
        var command = new InstallmentPurchaseCommand("Colchão", "900.00", 4, LocalDate.of(2026, 10, 20), null, null, key);
        assertThatThrownBy(() -> purchases.create(A, command)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from installment_purchases")).isZero();
        assertThat(count("select count(*) from installment_purchase_requests")).isZero();
        assertThat(count("select count(*) from installment_purchase_events")).isZero();
        assertThat(count("select count(*) from expense_entries")).isZero();

        jdbc.execute("alter table expense_entries drop constraint force_failure");
        var retry = purchases.create(A, command);
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.purchase().installments()).hasSize(4);
        assertThat(count("select count(*) from expense_entries")).isEqualTo(4);
    }

    @Test
    void longPurchasesFromThePastAreCreatedWholeWithExactSumAndOverdueInstallments() {
        var result = create(A, "99999999.99", 360, null, null, LocalDate.of(2025, 1, 31));
        var purchase = result.purchase();
        assertThat(purchase.installments()).hasSize(360);
        assertThat(purchase.installmentsSum()).isEqualTo("99999999.99");
        assertThat(purchase.installments().getFirst().amount()).isEqualTo("277777.77");
        assertThat(purchase.installments().getLast().amount()).isEqualTo("277780.56");
        assertThat(purchase.installments().get(1).dueDate()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(purchase.installments().get(37).dueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(purchase.lastDueDate()).isEqualTo(LocalDate.of(2054, 12, 31));
        assertThat(jdbc.queryForObject("select sum(charge_amount) from expense_entries", BigDecimal.class))
                .isEqualTo(new BigDecimal("99999999.99"));
        assertThat(count("select count(distinct installment_number) from expense_entries")).isEqualTo(360);
        // Past installments are ordinary pending entries, shown as overdue; nothing is paid automatically.
        assertThat(list(A, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31))).singleElement()
                .satisfies(e -> { assertThat(e.overdue()).isTrue(); assertThat(e.status()).isEqualTo(ExpenseStatus.PENDING); });
    }

    @Test
    void anInstallmentKeepsItsAmountButAcceptsIndividualMetadataCorrection() {
        var purchase = create(A, "100.00", 3, null, null).purchase();
        var second = purchase.installments().get(1);
        assertThatThrownBy(() -> tx.execute(s -> expenses.correct(A, new CorrectExpenseCommand(second.expenseId(), 0,
                ExpenseStatus.PENDING, "Sofá", "40.00", second.dueDate(), null, null, null, null, null, UUID.randomUUID()))))
                .isInstanceOf(ExpenseValidationException.class).hasMessageContaining("compra parcelada");
        var corrected = tx.execute(s -> expenses.correct(A, new CorrectExpenseCommand(second.expenseId(), 0,
                ExpenseStatus.PENDING, "Sofá da sala", "33.33", second.dueDate().plusDays(2), null, null, null, null, null,
                UUID.randomUUID())));
        assertThat(corrected.expense().installment()).isEqualTo(new InstallmentLink(purchase.id(), 2, 3));
        assertThat(jdbc.queryForList("select description from expense_entries where installment_purchase_id=? order by installment_number",
                String.class, purchase.id())).containsExactly("Compra", "Sofá da sala", "Compra");
        assertThat(jdbc.queryForObject("select sum(charge_amount) from expense_entries", BigDecimal.class))
                .isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void durableConstraintsProtectNumberingAndTheInstallmentLink() {
        var purchase = create(A, "10.00", 2, null, null).purchase();
        var insert = """
                insert into expense_entries(id,space_id,origin,description,charge_amount,charge_confirmed,status,due_date,
                    reference_date,created_by_user_id,created_at,version,installment_purchase_id,installment_number,installment_count)
                values (?,?,?,'X',1.00,true,'PENDING','2026-10-01','2026-10-01',?,?,0,?,?,?)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), SPACE, "INSTALLMENT", ADMIN, Timestamp.from(NOW),
                purchase.id(), 2, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), SPACE, "INSTALLMENT", ADMIN, Timestamp.from(NOW),
                null, 1, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), SPACE, "INSTALLMENT", ADMIN, Timestamp.from(NOW),
                purchase.id(), 3, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), SPACE, "ONE_OFF", ADMIN, Timestamp.from(NOW),
                purchase.id(), 1, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into installment_purchases(id,space_id,description,total_amount,installment_count,first_due_date,
                    created_by_user_id,created_at) values (?,?,'X',0.01,2,'2026-10-01',?,?)
                """, UUID.randomUUID(), SPACE, ADMIN, Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from expense_entries")).isEqualTo(2);
    }

    private InstallmentPurchaseCreationResult create(String email, String total, int count, UUID category, UUID responsible) {
        return create(email, total, count, category, responsible, LocalDate.of(2026, 10, 15));
    }

    private InstallmentPurchaseCreationResult create(String email, String total, int count, UUID category, UUID responsible,
            LocalDate first) {
        return purchases.create(email, new InstallmentPurchaseCommand("Compra", total, count, first, category, responsible,
                UUID.randomUUID()));
    }

    private List<ExpenseView> list(String email, LocalDate from, LocalDate to) {
        return expenses.list(email, new ExpenseListQuery(0, 100, ExpenseSort.REFERENCE_DATE, SortDirection.ASC, null,
                from, to, ExpenseDateBasis.DUE_DATE, null, false, null, false, null, ExpenseStatusFilter.ACTIVE, null)).content();
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private UUID insertCategory(UUID space, String name) {
        var id = UUID.randomUUID();
        jdbc.update("insert into expense_categories(id,space_id,name,normalized_name,version,created_by_user_id,created_at,updated_at) values(?,?,?,?,0,?,?,?)",
                id, space, name, name.toLowerCase(), space.equals(SPACE) ? ADMIN : OUTSIDER, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private AuthenticatedUserContextRepository contextRepository() {
        return email -> jdbc.query("""
                select u.id,u.display_name,u.normalized_email,s.id,s.name,m.role,s.currency_code,s.locale,s.time_zone
                  from identity_users u join space_memberships m on m.user_id=u.id and m.active=true join family_spaces s on s.id=m.space_id
                 where u.normalized_email=?
                """, (rs, row) -> new AuthenticatedUserContext(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), SpaceRole.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8),
                rs.getString(9)), email).stream().findFirst();
    }

    private void insertSpace(UUID id, String name) {
        jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values (?,?,'BRL','pt-BR','America/Sao_Paulo',?)",
                id, name, Timestamp.from(NOW));
    }

    private void insertUser(UUID id, String name, String email, UUID space, String role) {
        jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,?,?,'{test}x',true,?)",
                id, name, email, Timestamp.from(NOW));
        jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values (?,?,?,?,true,?)",
                UUID.randomUUID(), id, space, role, Timestamp.from(NOW));
    }
}
