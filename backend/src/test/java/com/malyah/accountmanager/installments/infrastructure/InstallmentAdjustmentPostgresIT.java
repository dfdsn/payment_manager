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
import java.util.Map;
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
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentAdjuster;
import com.malyah.accountmanager.expenses.infrastructure.JdbcInstallmentExpenses;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.installments.application.*;
import com.malyah.accountmanager.installments.domain.InstallmentChangeScope;
import com.malyah.accountmanager.installments.domain.InstallmentStateConflictException;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

/**
 * H05.3 on real PostgreSQL: group changes, due date recalculation and cancellation of pending installments with an
 * optional replacement purchase; paid installments preserved, impact token, idempotency, concurrency and rollback.
 */
@Testcontainers
class InstallmentAdjustmentPostgresIT {
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
            .withDatabaseName("account_manager_installment_adjustment_test").withUsername("account_manager")
            .withPassword("test-only-password");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InstallmentPurchaseUseCase purchases;
    private ExpenseService expenses;
    private InstallmentAdjustmentUseCase adjustments;

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
        adjustments = new TransactionalInstallmentAdjustmentUseCase(new InstallmentAdjustmentService(
                new JdbcInstallmentChangeRepository(jdbc), new JdbcInstallmentPurchaseRepository(jdbc), service,
                new JdbcInstallmentExpenses(jdbc, UUID::randomUUID), new JdbcInstallmentAdjuster(jdbc), context, members,
                clock, UUID::randomUUID), tx);
    }

    @Test
    void e05DemonstrationNonExactPurchaseOnePaidAndTheRestChangedWithoutTouchingThePaidOne() {
        var category = insertCategory(SPACE, "Móveis");
        var purchase = create(A, "100.00", 3, null, null, LocalDate.of(2026, 10, 31)).purchase();
        assertThat(purchase.installments()).extracting(InstallmentView::amount).containsExactly("33.33", "33.33", "33.34");
        var first = purchase.installments().getFirst();
        tx.execute(s -> expenses.settle(G, new SettleExpenseCommand(first.expenseId(), 0, "33.33",
                LocalDate.of(2026, 10, 30), GUEST, null, UUID.randomUUID())));
        var paidBefore = row(first.expenseId());

        // Preview from the paid installment is refused; from 2 it shows 2 and 3 and preserves the paid 1.
        assertThatThrownBy(() -> adjustments.previewChange(A, change(purchase.id(), 1, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description"), "X", null, null, null, null, null))).isInstanceOf(InstallmentStateConflictException.class);
        var command = change(purchase.id(), 2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description", "categoryId", "responsibleUserId"), "Sofá da sala", category, GUEST, null, null, null);
        var impact = adjustments.previewChange(A, command);
        assertThat(impact.affected()).extracting(AffectedInstallmentView::number).containsExactly(2, 3);
        assertThat(impact.preserved()).singleElement().isEqualTo(new PreservedInstallmentView(1, ExpenseStatus.PAID, "PAID"));
        assertThat(impact.affected().getFirst().changes()).extracting(InstallmentFieldChangeView::field)
                .containsExactly("description", "categoryId", "responsibleUserId");
        assertThat(count("select count(*) from installment_purchase_changes")).isZero();

        var result = adjustments.applyChange(A, withToken(command, impact.impactToken(), UUID.randomUUID()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.affectedCount()).isEqualTo(2);
        assertThat(result.preservedCount()).isEqualTo(1);
        assertThat(result.purchase().installments()).extracting(InstallmentView::description)
                .containsExactly("Compra", "Sofá da sala", "Sofá da sala");
        assertThat(result.purchase().installments()).extracting(InstallmentView::categoryName)
                .containsExactly(null, "Móveis", "Móveis");
        assertThat(result.purchase().installments()).extracting(InstallmentView::responsibleDisplayName)
                .containsExactly(null, "Convidado", "Convidado");
        assertThat(result.purchase().installments()).extracting(InstallmentView::amount).containsExactly("33.33", "33.33", "33.34");
        assertThat(row(first.expenseId())).isEqualTo(paidBefore);
        assertThat(jdbc.queryForObject("select sum(charge_amount) from expense_entries", BigDecimal.class))
                .isEqualByComparingTo("100.00");
        assertThat(count("select count(*) from expense_correction_events where installment_change_id=?", result.changeId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select change_type||'|'||scope||'|'||from_number||'|'||changed_fields||'|'||affected_count||'|'||preserved_count from installment_purchase_changes where id=?",
                String.class, result.changeId())).isEqualTo("CHANGE|THIS_AND_FOLLOWING|2|description,categoryId,responsibleUserId|2|1");
        var history = expenses.history(A, result.purchase().installments().get(2).expenseId(), 0, 20).content();
        assertThat(history).extracting(ExpenseHistoryEvent::type).contains("INSTALLMENT_CHANGE_APPLIED");
    }

    @Test
    void aNewDueDateMovesTheChosenAndRecalculatesTheFollowingPendingOnly() {
        var purchase = create(A, "60.00", 4, null, null, LocalDate.of(2027, 1, 31)).purchase();
        var third = purchase.installments().get(2);
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(third.expenseId(), 0, "15.00",
                LocalDate.of(2026, 9, 28), ADMIN, null, UUID.randomUUID())));

        var only = change(purchase.id(), 2, InstallmentChangeScope.THIS, List.of("dueDate"), null, null, null,
                LocalDate.of(2027, 3, 5), null, null);
        var onlyImpact = adjustments.previewChange(A, only);
        assertThat(onlyImpact.affected()).extracting(AffectedInstallmentView::number).containsExactly(2);
        assertThat(onlyImpact.preserved()).extracting(PreservedInstallmentView::reason)
                .containsExactly("BEFORE_START", "PAID", "OUTSIDE_SCOPE");

        var following = change(purchase.id(), 2, InstallmentChangeScope.THIS_AND_FOLLOWING, List.of("dueDate"), null,
                null, null, LocalDate.of(2027, 3, 5), null, null);
        var impact = adjustments.previewChange(A, following);
        var result = adjustments.applyChange(A, withToken(following, impact.impactToken(), UUID.randomUUID()));
        assertThat(result.purchase().installments()).extracting(InstallmentView::dueDate).containsExactly(
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 5), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 5, 5));
        assertThat(result.purchase().installments()).extracting(InstallmentView::status).containsExactly(
                ExpenseStatus.PENDING, ExpenseStatus.PENDING, ExpenseStatus.PAID, ExpenseStatus.PENDING);
        assertThat(jdbc.queryForList("select reference_date from expense_entries where installment_purchase_id=? order by installment_number",
                LocalDate.class, purchase.id())).containsExactly(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 5),
                LocalDate.of(2027, 3, 31), LocalDate.of(2027, 5, 5));
    }

    @Test
    void cancellingSelectedPendingInstallmentsWithAReplacementIsOneAtomicOperation() {
        var purchase = create(A, "100.00", 3, null, null, LocalDate.of(2026, 10, 31)).purchase();
        var first = purchase.installments().getFirst();
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(first.expenseId(), 0, "33.33",
                LocalDate.of(2026, 10, 30), ADMIN, null, UUID.randomUUID())));
        var paidBefore = row(first.expenseId());
        var replacement = new InstallmentPurchaseCommand("Sofá (restante)", "70.00", 4, LocalDate.of(2026, 11, 30), null,
                null, null);
        var command = new InstallmentCancellationCommand(purchase.id(), List.of(3, 2), "Renegociei o restante",
                replacement, null, null);

        var impact = adjustments.previewCancellation(A, command);
        assertThat(impact.affected()).extracting(AffectedInstallmentView::number).containsExactly(2, 3);
        assertThat(impact.affectedAmount()).isEqualTo("66.67");
        assertThat(impact.replacement().installments()).extracting(InstallmentView::amount)
                .containsExactly("17.50", "17.50", "17.50", "17.50");
        assertThat(count("select count(*) from installment_purchases")).isOne();

        var result = adjustments.applyCancellation(G, cancel(command, impact.impactToken(), UUID.randomUUID()));

        assertThat(result.purchase().installments()).extracting(InstallmentView::status)
                .containsExactly(ExpenseStatus.PAID, ExpenseStatus.CANCELLED, ExpenseStatus.CANCELLED);
        assertThat(result.purchase().progress().cancelledAmount()).isEqualTo("66.67");
        assertThat(result.replacement().replacesPurchaseId()).isEqualTo(purchase.id());
        assertThat(result.replacement().installmentsSum()).isEqualTo("70.00");
        assertThat(result.replacement().createdByDisplayName()).isEqualTo("Convidado");
        // No refund or reversal: the paid installment keeps payment, version and history.
        assertThat(row(first.expenseId())).isEqualTo(paidBefore);
        assertThat(jdbc.queryForList("select cancellation_reason from expense_entries where status='CANCELLED'", String.class))
                .containsOnly("Renegociei o restante").hasSize(2);
        assertThat(count("select count(*) from expense_cancellation_events where installment_change_id=?", result.changeId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select replacement_purchase_id from installment_purchase_changes where id=?",
                UUID.class, result.changeId())).isEqualTo(result.replacement().id());
        assertThat(expenses.history(A, purchase.installments().get(1).expenseId(), 0, 20).content())
                .extracting(ExpenseHistoryEvent::type).contains("INSTALLMENT_CANCELLED");
        // Active entries: the paid 33.33 plus the replacement 70.00.
        assertThat(jdbc.queryForObject("select sum(charge_amount) from expense_entries where status<>'CANCELLED'",
                BigDecimal.class)).isEqualByComparingTo("103.33");

        assertThatThrownBy(() -> adjustments.previewCancellation(A, new InstallmentCancellationCommand(purchase.id(),
                List.of(1), "x", null, null, null))).isInstanceOf(InstallmentStateConflictException.class);
    }

    @Test
    void aStaleReviewIsRefusedWithoutWritingAnything() {
        var purchase = create(A, "90.00", 3, null, null, LocalDate.of(2026, 10, 10)).purchase();
        var command = new InstallmentCancellationCommand(purchase.id(), List.of(2, 3), "Desisti", null, null, null);
        var impact = adjustments.previewCancellation(A, command);
        var change = change(purchase.id(), 1, InstallmentChangeScope.THIS_AND_FOLLOWING, List.of("description"), "Nova",
                null, null, null, null, null);
        var changeImpact = adjustments.previewChange(A, change);

        // Installment 3 is paid meanwhile through Despesas.
        var third = purchase.installments().get(2);
        tx.execute(s -> expenses.settle(G, new SettleExpenseCommand(third.expenseId(), 0, "30.00",
                LocalDate.of(2026, 9, 28), GUEST, null, UUID.randomUUID())));

        assertThatThrownBy(() -> adjustments.applyCancellation(A, cancel(command, impact.impactToken(), UUID.randomUUID())))
                .isInstanceOf(InstallmentStateConflictException.class);
        assertThatThrownBy(() -> adjustments.applyChange(A, withToken(change, changeImpact.impactToken(), UUID.randomUUID())))
                .isInstanceOf(InstallmentImpactChangedException.class);
        assertThatThrownBy(() -> adjustments.applyChange(A, withToken(change, "forged", UUID.randomUUID())))
                .isInstanceOf(InstallmentImpactChangedException.class);
        assertThat(count("select count(*) from installment_purchase_changes")).isZero();
        assertThat(count("select count(*) from installment_change_requests")).isZero();
        assertThat(count("select count(*) from expense_entries where status='CANCELLED'")).isZero();
        assertThat(count("select count(*) from expense_correction_events")).isZero();
    }

    @Test
    void theSameKeyReplaysAndAReusedKeyWithOtherDataIsRejectedEvenConcurrently() throws Exception {
        var purchase = create(A, "40.00", 4, null, null, LocalDate.of(2026, 10, 10)).purchase();
        var change = change(purchase.id(), 1, InstallmentChangeScope.THIS_AND_FOLLOWING, List.of("description"),
                "Notebook", null, null, null, null, null);
        var token = adjustments.previewChange(A, change).impactToken();
        var key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var results = new java.util.ArrayList<InstallmentChangeResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<InstallmentChangeResult>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> {
                start.await();
                return adjustments.applyChange(A, withToken(change, token, key));
            }));
            start.countDown();
            for (var task : tasks) results.add(task.get());
        }
        assertThat(results).extracting(InstallmentChangeResult::changeId).containsOnly(results.getFirst().changeId());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(count("select count(*) from installment_purchase_changes")).isOne();
        assertThat(count("select count(*) from expense_correction_events")).isEqualTo(4);

        // After the change the old token is stale, but a replay of the same request still returns the first result.
        assertThat(adjustments.applyChange(A, withToken(change, token, key)).replayed()).isTrue();
        assertThatThrownBy(() -> adjustments.applyChange(A, withToken(change(purchase.id(), 1,
                InstallmentChangeScope.THIS, List.of("description"), "Outro", null, null, null, null, null), token, key)))
                .isInstanceOf(InstallmentIdempotencyConflictException.class);
        assertThatThrownBy(() -> adjustments.applyChange(A, withToken(change, token, null)))
                .isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("Idempotency-Key");
    }

    @Test
    void concurrentCancellationsOfTheSamePurchaseApplyOnlyOnce() throws Exception {
        var purchase = create(A, "40.00", 4, null, null, LocalDate.of(2026, 10, 10)).purchase();
        var command = new InstallmentCancellationCommand(purchase.id(), List.of(3, 4), "Duplicada", null, null, null);
        var token = adjustments.previewCancellation(A, command).impactToken();
        var start = new CountDownLatch(1);
        var outcomes = new java.util.ArrayList<Object>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
            for (var email : List.of(A, G)) tasks.add(executor.submit(() -> {
                start.await();
                try { return adjustments.applyCancellation(email, cancel(command, token, UUID.randomUUID())); }
                catch (RuntimeException e) { return e; }
            }));
            start.countDown();
            for (var task : tasks) outcomes.add(task.get());
        }
        assertThat(outcomes).filteredOn(InstallmentChangeResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(InstallmentStateConflictException.class::isInstance).hasSize(1);
        assertThat(count("select count(*) from expense_cancellation_events")).isEqualTo(2);
    }

    @Test
    void referencesSpacesAndFailuresAreCheckedAndRolledBackEntirely() {
        var purchase = create(A, "30.00", 3, null, null, LocalDate.of(2026, 10, 10)).purchase();
        var archived = insertCategory(SPACE, "Antiga");
        jdbc.update("update expense_categories set archived_at=? where id=?", Timestamp.from(NOW), archived);
        var foreignCategory = insertCategory(OTHER, "Alheia");
        assertThatThrownBy(() -> adjustments.previewChange(A, change(purchase.id(), 1, InstallmentChangeScope.THIS,
                List.of("categoryId"), null, archived, null, null, null, null))).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(() -> adjustments.previewChange(A, change(purchase.id(), 1, InstallmentChangeScope.THIS,
                List.of("categoryId"), null, foreignCategory, null, null, null, null))).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(() -> adjustments.previewChange(A, change(purchase.id(), 1, InstallmentChangeScope.THIS,
                List.of("responsibleUserId"), null, null, OUTSIDER, null, null, null)))
                .isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("responsibleUserId");
        assertThatThrownBy(() -> adjustments.previewChange(O, change(purchase.id(), 1, InstallmentChangeScope.THIS,
                List.of("description"), "X", null, null, null, null, null))).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> adjustments.applyCancellation(O, new InstallmentCancellationCommand(purchase.id(),
                List.of(1), "x", null, "t", UUID.randomUUID()))).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> adjustments.previewCancellation(A, new InstallmentCancellationCommand(purchase.id(),
                List.of(1), "x", new InstallmentPurchaseCommand("R", "0.01", 2, LocalDate.of(2026, 11, 1), null, null, null),
                null, null))).isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("totalAmount");

        // A failure while creating the replacement undoes the cancellation too.
        var command = new InstallmentCancellationCommand(purchase.id(), List.of(2, 3), "Troca",
                new InstallmentPurchaseCommand("Troca", "20.00", 2, LocalDate.of(2026, 11, 1), null, null, null), null, null);
        var token = adjustments.previewCancellation(A, command).impactToken();
        jdbc.execute("alter table expense_entries add constraint force_failure check (description <> 'Troca')");
        assertThatThrownBy(() -> adjustments.applyCancellation(A, cancel(command, token, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("alter table expense_entries drop constraint force_failure");
        assertThat(count("select count(*) from installment_purchases")).isOne();
        assertThat(count("select count(*) from expense_entries where status='CANCELLED'")).isZero();
        assertThat(count("select count(*) from installment_purchase_changes")).isZero();
        assertThat(count("select count(*) from installment_change_requests")).isZero();
        // A member who left can no longer change the purchase.
        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW), ADMIN, GUEST);
        assertThatThrownBy(() -> adjustments.applyCancellation(G, cancel(command, token, UUID.randomUUID())))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    @Test
    void durableConstraintsKeepTheAuditConsistent() {
        var purchase = create(A, "30.00", 3, null, null, LocalDate.of(2026, 10, 10)).purchase();
        var insert = """
                insert into installment_purchase_changes(id,purchase_id,space_id,actor_user_id,change_type,scope,from_number,
                    changed_fields,reason,impact_hash,affected_count,preserved_count,occurred_at)
                values (?,?,?,?,?,?,?,?,?,?,1,0,?)
                """;
        var hash = "a".repeat(64);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), purchase.id(), SPACE, ADMIN, "CANCELLATION",
                null, null, null, null, hash, Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), purchase.id(), SPACE, ADMIN, "CHANGE",
                "THIS", 1, null, null, hash, Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update installment_purchases set replaces_purchase_id=id where id=?",
                purchase.id())).isInstanceOf(DataIntegrityViolationException.class);
        var change = UUID.randomUUID();
        jdbc.update(insert, change, purchase.id(), SPACE, ADMIN, "CANCELLATION", null, null, null, "motivo", hash,
                Timestamp.from(NOW));
        var recurrenceChange = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                insert into expense_cancellation_events(id,expense_id,space_id,actor_user_id,reason,cancelled_at,
                    from_version,to_version,recurrence_change_id,installment_change_id)
                values (?,?,?,?,'x',?,0,1,?,?)
                """, UUID.randomUUID(), purchase.installments().getFirst().expenseId(), SPACE, ADMIN, Timestamp.from(NOW),
                recurrenceChange, change)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, Object> row(UUID expenseId) {
        return jdbc.queryForMap("""
                select status, version, description, due_date, category_id, responsible_user_id, paid_amount,
                       payment_date, paid_by_user_id, cancelled_at from expense_entries where id=?
                """, expenseId);
    }

    private static InstallmentChangeCommand change(UUID purchase, int from, InstallmentChangeScope scope,
            List<String> fields, String description, UUID category, UUID responsible, LocalDate due, String token,
            UUID key) {
        return new InstallmentChangeCommand(purchase, from, scope, fields, description, category, responsible, due,
                token, key);
    }

    private static InstallmentChangeCommand withToken(InstallmentChangeCommand c, String token, UUID key) {
        return new InstallmentChangeCommand(c.purchaseId(), c.fromNumber(), c.scope(), c.changedFields(), c.description(),
                c.categoryId(), c.responsibleUserId(), c.dueDate(), token, key);
    }

    private static InstallmentCancellationCommand cancel(InstallmentCancellationCommand c, String token, UUID key) {
        return new InstallmentCancellationCommand(c.purchaseId(), c.installmentNumbers(), c.reason(), c.replacement(),
                token, key);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

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
