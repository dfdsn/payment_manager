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

/**
 * H05.2 on real PostgreSQL: purchases listed with progress derived from their installments, installments paid through
 * the ordinary E02 settlement (individual and atomic batch), no double counting and isolation between spaces.
 */
@Testcontainers
class InstallmentProgressPostgresIT {
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
            .withDatabaseName("account_manager_installment_progress_test").withUsername("account_manager")
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(22);
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
    void installmentsArePaidThroughE02AndTheProgressFollowsTheirSituations() {
        // Today is 2026-09-28 in São Paulo: the first installment (2026-09-15) is already overdue.
        var sofa = create(A, "100.00", 3, null, null, LocalDate.of(2026, 9, 15)).purchase();
        var table = create(G, "50.00", 2, null, null, LocalDate.of(2026, 10, 5)).purchase();
        var oneOff = tx.execute(s -> expenses.create(A, new CreateOneOffExpenseCommand("Luz", "80.00", ExpenseStatus.PENDING,
                LocalDate.of(2026, 10, 10), null, null, UUID.randomUUID(), null, null, null, null, null))).expense();

        assertThat(purchases.get(A, sofa.id()).progress()).isEqualTo(new InstallmentProgress(3, 0, 3, 1, 0, "0.00",
                "100.00", "33.33", "0.00", LocalDate.of(2026, 9, 15)));
        assertThat(purchases.get(A, sofa.id()).installments().getFirst().overdue()).isTrue();

        // Individual settlement of the overdue installment, with the ordinary E02 rules (the amount paid may differ).
        var first = purchases.get(A, sofa.id()).installments().getFirst();
        tx.execute(s -> expenses.settle(G, new SettleExpenseCommand(first.expenseId(), first.version(), "33.00",
                LocalDate.of(2026, 9, 20), GUEST, "Pix", UUID.randomUUID())));

        // Atomic batch mixing installments of two purchases and an ordinary expense.
        var second = purchases.get(A, sofa.id()).installments().get(1);
        var tableFirst = purchases.get(A, table.id()).installments().getFirst();
        var batch = tx.execute(s -> expenses.settleBatch(A, new BatchSettlementCommand(List.of(
                new BatchSettlementItem(second.expenseId(), second.version()),
                new BatchSettlementItem(tableFirst.expenseId(), tableFirst.version()),
                new BatchSettlementItem(oneOff.id(), oneOff.version())), LocalDate.of(2026, 9, 28), ADMIN, true,
                UUID.randomUUID())));
        assertThat(batch.items()).hasSize(3);

        var detail = purchases.get(G, sofa.id());
        assertThat(detail.progress()).isEqualTo(new InstallmentProgress(3, 2, 1, 0, 0, "66.66", "33.34", "0.00", "0.00",
                LocalDate.of(2026, 11, 15)));
        assertThat(detail.installments()).extracting(InstallmentView::status)
                .containsExactly(ExpenseStatus.PAID, ExpenseStatus.PAID, ExpenseStatus.PENDING);
        assertThat(detail.installments()).extracting(InstallmentView::paymentDate)
                .containsExactly(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 28), null);
        assertThat(detail.installments()).extracting(InstallmentView::paidAmount).containsExactly("33.00", "33.33", null);
        assertThat(detail.installments().get(1).version()).isEqualTo(second.version() + 1);
        // The purchase total is informative: the charges still add up to it and nothing is recalculated.
        assertThat(detail.installmentsSum()).isEqualTo("100.00");

        var page = purchases.list(A, 0, 20);
        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items()).extracting(InstallmentPurchaseSummary::description).containsExactly("Compra", "Compra");
        assertThat(page.items()).extracting(p -> p.progress().paidCount()).containsExactlyInAnyOrder(2, 1);

        // Only installments are expenses: October has sofa 2/3, table 1/2 and the bill; the totals are never added again.
        var october = list(A, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october).extracting(ExpenseView::amount).containsExactlyInAnyOrder("33.33", "25.00", "80.00");
        assertThat(sum(list(A, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))))
                .isEqualByComparingTo("230.00");
        assertThat(count("select count(*) from expense_entries")).isEqualTo(6);
    }

    @Test
    void aBatchWithAStaleInstallmentIsRejectedWholeAndTheProgressIsUnchanged() {
        var purchase = create(A, "90.00", 3, null, null, LocalDate.of(2026, 10, 1)).purchase();
        var installments = purchase.installments();
        // Someone corrects installment 2 meanwhile: the reviewed version is stale.
        tx.execute(s -> expenses.correct(G, new CorrectExpenseCommand(installments.get(1).expenseId(), 0,
                ExpenseStatus.PENDING, "Compra revisada", "30.00", installments.get(1).dueDate(), null, null, null, null,
                null, UUID.randomUUID())));

        assertThatThrownBy(() -> tx.execute(s -> expenses.settleBatch(A, new BatchSettlementCommand(
                installments.stream().map(i -> new BatchSettlementItem(i.expenseId(), i.version())).toList(),
                LocalDate.of(2026, 9, 28), ADMIN, true, UUID.randomUUID()))))
                .isInstanceOf(BatchSettlementConflictException.class);

        var after = purchases.get(A, purchase.id());
        assertThat(after.progress().paidCount()).isZero();
        assertThat(after.progress().pendingCount()).isEqualTo(3);
        assertThat(after.installments().get(1).description()).isEqualTo("Compra revisada");
        assertThat(after.installments().get(0).description()).isEqualTo("Compra");
        assertThat(count("select count(*) from expense_entries where status='PAID'")).isZero();
    }

    @Test
    void cancellationAndReversalThroughE02AreReflectedWithoutTouchingOtherInstallments() {
        var purchase = create(A, "60.00", 3, null, null, LocalDate.of(2026, 10, 1)).purchase();
        var first = purchase.installments().get(0);
        var third = purchase.installments().get(2);
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(first.expenseId(), 0, "20.00",
                LocalDate.of(2026, 9, 28), ADMIN, null, UUID.randomUUID())));
        tx.execute(s -> expenses.cancel(A, new CancelExpenseCommand(third.expenseId(), 0, "Loja cancelou a parcela",
                UUID.randomUUID())));

        assertThat(purchases.get(A, purchase.id()).progress()).isEqualTo(new InstallmentProgress(3, 1, 1, 0, 1, "20.00",
                "20.00", "0.00", "20.00", LocalDate.of(2026, 11, 1)));

        tx.execute(s -> expenses.reversePayment(A, new ReversePaymentCommand(first.expenseId(), 1, "Pago em duplicidade",
                UUID.randomUUID())));
        var after = purchases.get(A, purchase.id());
        assertThat(after.progress().paidCount()).isZero();
        assertThat(after.progress().pendingCount()).isEqualTo(2);
        assertThat(after.progress().nextDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(after.installments().getFirst().paymentDate()).isNull();
        assertThat(after.installments().getFirst().paidAmount()).isNull();
    }

    @Test
    void purchasesAreIsolatedPerSpaceAndPagedNewestFirst() {
        var ids = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            var id = create(A, "10.00", 2, null, null, LocalDate.of(2026, 10, 1)).purchase().id();
            jdbc.update("update installment_purchases set created_at=? where id=?", Timestamp.from(NOW.plusSeconds(i)), id);
            ids.add(id);
        }
        var foreign = create(O, "10.00", 2, null, null, LocalDate.of(2026, 10, 1)).purchase();

        var firstPage = purchases.list(G, 0, 2);
        assertThat(firstPage.totalItems()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(InstallmentPurchaseSummary::id).containsExactly(ids.get(2), ids.get(1));
        assertThat(purchases.list(G, 1, 2).items()).extracting(InstallmentPurchaseSummary::id).containsExactly(ids.get(0));
        assertThat(purchases.list(G, 5, 2).items()).isEmpty();
        assertThat(purchases.list(O, 0, 20).items()).extracting(InstallmentPurchaseSummary::id)
                .containsExactly(foreign.id());
        assertThat(firstPage.items().getFirst().progress().installmentCount()).isEqualTo(2);

        assertThatThrownBy(() -> purchases.get(A, foreign.id())).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> purchases.get(O, ids.getFirst())).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> purchases.get(A, UUID.randomUUID())).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        // A member who left the space no longer reads its purchases.
        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW), ADMIN, GUEST);
        assertThatThrownBy(() -> purchases.list(G, 0, 20)).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> purchases.get(G, ids.getFirst())).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    private static BigDecimal sum(List<ExpenseView> views) {
        return views.stream().map(v -> new BigDecimal(v.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
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
