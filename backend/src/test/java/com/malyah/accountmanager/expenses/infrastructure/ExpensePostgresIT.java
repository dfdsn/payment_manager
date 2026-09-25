package com.malyah.accountmanager.expenses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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

import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseIdempotencyConflictException;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

@Testcontainers
class ExpensePostgresIT {
    private static final Instant NOW = Instant.parse("2026-09-25T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID OTHER_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_expense_test")
            .withUsername("account_manager")
            .withPassword("test-only-password");

    private JdbcTemplate jdbc;
    private ExpenseUseCase useCase;

    @BeforeEach
    void reset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        jdbc = new JdbcTemplate(dataSource);
        insertSpaceAndMembers();
        var context = new AuthenticatedUserContextService(contextRepository());
        var service = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID,
                Clock.fixed(NOW, ZoneOffset.UTC), new com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess(jdbc));
        useCase = new TransactionalExpenseUseCase(
                service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void administratorAndGuestCreatePendingAndPaidExpensesThenReadOnlyTheirSpace() {
        assertThat(useCase.list("admin@example.com",
                new ExpenseListQuery(0, 20, ExpenseSort.REFERENCE_DATE, SortDirection.ASC)).content()).isEmpty();
        var pending = useCase.create("admin@example.com", command(
                "Energia", "150.00", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 24), null, UUID.randomUUID()));
        var paid = useCase.create("guest@example.com", command(
                "Mercado", "25.50", ExpenseStatus.PAID, null, LocalDate.of(2026, 9, 25), UUID.randomUUID()));
        insertOtherSpaceExpense();

        var page = useCase.list("guest@example.com",
                new ExpenseListQuery(0, 20, ExpenseSort.REFERENCE_DATE, SortDirection.ASC));

        assertThat(pending.expense().overdue()).isTrue();
        assertThat(paid.expense().paidAmount()).isEqualTo("25.50");
        assertThat(paid.expense().paidByUserId()).isEqualTo(GUEST);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(expense -> expense.description())
                .containsExactly("Energia", "Mercado");
        assertThat(page.content()).allSatisfy(expense -> {
            assertThat(expense.categoryName()).isNull();
            assertThat(expense.responsibleUserId()).isNull();
        });
    }

    @Test
    void sameRequestIsIdempotentConcurrentAndNewKeyAllowsLegitimateDuplicate() throws Exception {
        var key = UUID.randomUUID();
        Callable<UUID> create = () -> useCase.create("admin@example.com", command(
                "Internet", "99.90", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 30), null, key)).expense().id();

        List<UUID> ids;
        try (var executor = Executors.newFixedThreadPool(2)) {
            ids = executor.invokeAll(List.of(create, create)).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();
        }

        assertThat(ids).hasSize(2).allMatch(ids.getFirst()::equals);
        assertThat(useCase.create("admin@example.com", command(
                "Internet", "99.90", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 30), null, key)).replayed())
                .isTrue();
        assertThat(jdbc.queryForObject("select count(*) from expense_entries where space_id = ?", Integer.class, SPACE))
                .isOne();

        useCase.create("admin@example.com", command(
                "Internet", "99.90", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 30), null, UUID.randomUUID()));
        assertThat(jdbc.queryForObject("select count(*) from expense_entries where space_id = ?", Integer.class, SPACE))
                .isEqualTo(2);
    }

    @Test
    void reusedKeyWithDifferentPayloadConflictsAndPaginationOrderingIsStable() {
        var key = UUID.randomUUID();
        useCase.create("admin@example.com", command(
                "B", "20.00", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 2), null, key));
        assertThatThrownBy(() -> useCase.create("admin@example.com", command(
                "Alterada", "20.00", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 2), null, key)))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);
        useCase.create("admin@example.com", command(
                "A", "10.00", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 1), null, UUID.randomUUID()));
        useCase.create("admin@example.com", command(
                "C", "30.00", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 3), null, UUID.randomUUID()));

        var first = useCase.list("admin@example.com",
                new ExpenseListQuery(0, 2, ExpenseSort.DESCRIPTION, SortDirection.ASC));
        var second = useCase.list("admin@example.com",
                new ExpenseListQuery(1, 2, ExpenseSort.DESCRIPTION, SortDirection.ASC));
        assertThat(first.content()).extracting(expense -> expense.description()).containsExactly("A", "B");
        assertThat(second.content()).extracting(expense -> expense.description()).containsExactly("C");
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(useCase.list("admin@example.com",
                new ExpenseListQuery(0, 3, ExpenseSort.AMOUNT, SortDirection.DESC)).content())
                .extracting(expense -> expense.amount()).containsExactly("30.00", "20.00", "10.00");
    }

    @Test
    void inactiveMemberCannotCreateOrListAndDatabaseRejectsInvalidFinancialRows() {
        jdbc.update("""
                update space_memberships
                   set active = false, ended_at = ?, ended_by_user_id = ?, end_reason = 'VOLUNTARY_EXIT'
                 where user_id = ?
                """, Timestamp.from(NOW), GUEST, GUEST);
        assertThatThrownBy(() -> useCase.list("guest@example.com",
                new ExpenseListQuery(0, 20, ExpenseSort.REFERENCE_DATE, SortDirection.ASC)))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> useCase.create("guest@example.com", command(
                "Sem acesso", "1.00", ExpenseStatus.PENDING, LocalDate.now(), null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed,
                    status, due_date, reference_date, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Inválida', -1, true, 'PENDING', ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), SPACE, LocalDate.now(), LocalDate.now(), ADMIN, Timestamp.from(NOW)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private CreateOneOffExpenseCommand command(
            String description, String amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, UUID key) {
        return new CreateOneOffExpenseCommand(description, amount, status, dueDate, paymentDate, null, key);
    }

    private com.malyah.accountmanager.expenses.application.SettleExpenseCommand payment(UUID expense, String amount, UUID key) {
        return new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                expense, 0, amount, LocalDate.of(2026, 10, 1), GUEST, "Pagamento integral", key);
    }

    private UUID pending() {
        return useCase.create("admin@example.com", command("Conta", "150", ExpenseStatus.PENDING,
                LocalDate.of(2026, 9, 30), null, UUID.randomUUID())).expense().id();
    }

    @Test void settlementKeepsChargeDueDateAndDistinctPayerAuthorWithSingleAudit() {
        for (var amount : List.of("155.00", "145.00")) {
            var id = pending();
            var command = payment(id, amount, UUID.randomUUID());
            var result = useCase.settle("admin@example.com", command);
            assertThat(result.expense().status()).isEqualTo(ExpenseStatus.PAID);
            assertThat(result.expense().amount()).isEqualTo("150.00");
            assertThat(result.expense().paidAmount()).isEqualTo(amount);
            assertThat(result.expense().paidByUserId()).isEqualTo(GUEST);
            assertThat(result.expense().paymentAudit().recordedByUserId()).isEqualTo(ADMIN);
            assertThat(result.expense().paymentAudit().recordedAt()).isEqualTo(NOW);
            assertThat(result.expense().dueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(result.expense().paymentDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(result.expense().version()).isOne();
            assertThat(useCase.settle("admin@example.com", command).replayed()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?", Integer.class, id)).isOne();
            assertThatThrownBy(() -> useCase.settle("admin@example.com", payment(id, "140", command.idempotencyKey())))
                    .isInstanceOf(ExpenseIdempotencyConflictException.class);
            assertThatThrownBy(() -> useCase.settle("guest@example.com", payment(id, "140", UUID.randomUUID())))
                    .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);
        }
    }

    @Test void paidCreationUsesSamePaymentRulesAndRecordsAuditAtomically() {
        var result = useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Compra", "150", ExpenseStatus.PAID, null, LocalDate.of(2026, 10, 1), null,
                UUID.randomUUID(), "145", GUEST, "Desconto"));
        assertThat(result.expense().paidAmount()).isEqualTo("145.00");
        assertThat(result.expense().paidByUserId()).isEqualTo(GUEST);
        assertThat(result.expense().paymentAudit().recordedByUserId()).isEqualTo(ADMIN);
        assertThat(result.expense().referenceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events", Integer.class)).isOne();
    }

    @Test void refusesForeignExpensePayerInactiveMembershipAndStaleVersion() {
        var id = pending();
        assertThatThrownBy(() -> useCase.settle("other@example.com", new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                id, 0, "150", LocalDate.now(), UUID.fromString("00000000-0000-0000-0000-000000000201"), null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseNotFoundException.class);
        assertThatThrownBy(() -> useCase.settle("admin@example.com", new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                id, 0, "150", LocalDate.now(), UUID.randomUUID(), null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        jdbc.update("update expense_entries set version=1 where id=?", id);
        assertThatThrownBy(() -> useCase.settle("admin@example.com", payment(id, "150", UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);
        jdbc.update("update space_memberships set active=false, ended_at=?, ended_by_user_id=?, end_reason='VOLUNTARY_EXIT' where user_id=?",
                Timestamp.from(NOW), GUEST, GUEST);
        assertThatThrownBy(() -> useCase.settle("guest@example.com", payment(id, "150", UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events", Integer.class)).isZero();
    }

    @Test void concurrentSameKeyReplaysAndDifferentKeysNeverReplaceFirstPayment() throws Exception {
        for (boolean sameKey : List.of(true, false)) {
            var id = pending();
            var key = UUID.randomUUID();
            Callable<String> first = () -> { useCase.settle("admin@example.com", payment(id, "155", key)); return "paid"; };
            Callable<String> second = () -> {
                try { useCase.settle("admin@example.com", payment(id, "155", sameKey ? key : UUID.randomUUID())); return "paid"; }
                catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
            };
            // Either different-key contender may win; both branches explicitly observe a conflict.
            Callable<String> safeFirst = () -> { try { return first.call(); }
                catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; } };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = executor.invokeAll(List.of(safeFirst, second));
                var outcomes = List.of(results.get(0).get(), results.get(1).get());
                if (sameKey) assertThat(outcomes).containsExactly("paid", "paid");
                else assertThat(outcomes).containsExactlyInAnyOrder("paid", "conflict");
            }
            assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?", Integer.class, id)).isOne();
        }
    }

    @Test void auditFailureRollsBackPaymentStatusVersionAndIdempotencyThenRetryWorks() {
        var id = pending();
        var command = payment(id, "155", UUID.randomUUID());
        jdbc.execute("alter table expense_payment_events add constraint test_audit_failure check (paid_amount < 155)");
        assertThatThrownBy(() -> useCase.settle("admin@example.com", command))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select status from expense_entries where id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select version from expense_entries where id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from expense_idempotency_requests where operation='SETTLE_EXPENSE'", Integer.class)).isZero();
        jdbc.execute("alter table expense_payment_events drop constraint test_audit_failure");
        assertThat(useCase.settle("admin@example.com", command).expense().status()).isEqualTo(ExpenseStatus.PAID);
    }

    @Test void correctsPendingAndPaidFieldsAndPreservesBeforeAfterHistory() {
        var pendingId = pending();
        var pendingCorrection = correction(pendingId, 0, ExpenseStatus.PENDING, "Conta corrigida", "160",
                LocalDate.of(2026, 10, 2), "Nova observação", null, null, null, null, UUID.randomUUID());
        var pendingResult = useCase.correct("guest@example.com", pendingCorrection);
        assertThat(pendingResult.expense().description()).isEqualTo("Conta corrigida");
        assertThat(pendingResult.expense().amount()).isEqualTo("160.00");
        assertThat(pendingResult.expense().dueDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(pendingResult.expense().version()).isOne();
        assertThat(useCase.correct("guest@example.com", pendingCorrection).replayed()).isTrue();
        assertThatThrownBy(() -> useCase.correct("guest@example.com", correction(pendingId, 0,
                ExpenseStatus.PENDING, "Outro conteúdo", "160", LocalDate.of(2026, 10, 2),
                "Nova observação", null, null, null, null, pendingCorrection.idempotencyKey())))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);
        assertThat(jdbc.queryForMap("select * from expense_correction_events where expense_id=?", pendingId))
                .containsEntry("old_description", "Conta")
                .containsEntry("new_description", "Conta corrigida")
                .containsEntry("actor_user_id", GUEST)
                .containsEntry("changed_fields", "description,amount,dueDate,notes");

        var paid = useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Mercado", "150", ExpenseStatus.PAID, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), null,
                UUID.randomUUID(), "155", GUEST, "Juros"));
        var paidCorrection = correction(paid.expense().id(), 0, ExpenseStatus.PAID, "Mercado ajustado", "149",
                LocalDate.of(2026, 10, 3), "Nota", "145", LocalDate.of(2026, 10, 2), ADMIN,
                "Desconto confirmado", UUID.randomUUID());
        var corrected = useCase.correct("guest@example.com", paidCorrection).expense();
        assertThat(corrected.status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(corrected.amount()).isEqualTo("149.00");
        assertThat(corrected.paidAmount()).isEqualTo("145.00");
        assertThat(corrected.paidByUserId()).isEqualTo(ADMIN);
        assertThat(corrected.paymentDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(corrected.paymentAudit().recordedByUserId()).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?", Integer.class,
                paid.expense().id())).isOne();
        assertThat(jdbc.queryForMap("select * from expense_correction_events where expense_id=?", paid.expense().id()))
                .containsEntry("old_paid_amount", new java.math.BigDecimal("155.00"))
                .containsEntry("new_paid_amount", new java.math.BigDecimal("145.00"))
                .containsEntry("old_payer_user_id", GUEST)
                .containsEntry("new_payer_user_id", ADMIN);
    }

    @Test void correctionRejectsForeignInactiveInvalidPayerStateChangeAndNoOp() {
        var id = pending();
        var valid = correction(id, 0, ExpenseStatus.PENDING, "Corrigida", "150",
                LocalDate.of(2026, 9, 30), null, null, null, null, null, UUID.randomUUID());
        assertThatThrownBy(() -> useCase.correct("other@example.com", valid))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseNotFoundException.class);
        assertThatThrownBy(() -> useCase.correct("admin@example.com", correction(id, 0, ExpenseStatus.PAID,
                "Corrigida", "150", null, null, "150", LocalDate.now(), GUEST, null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);
        assertThatThrownBy(() -> useCase.correct("admin@example.com", correction(id, 0, ExpenseStatus.PENDING,
                "Conta", "150", LocalDate.of(2026, 9, 30), null, null, null, null, null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException.class);
        var paid = useCase.settle("admin@example.com", payment(id, "150", UUID.randomUUID())).expense();
        assertThatThrownBy(() -> useCase.correct("admin@example.com", correction(id, paid.version(), ExpenseStatus.PAID,
                paid.description(), paid.amount(), paid.dueDate(), paid.notes(), paid.paidAmount(), paid.paymentDate(),
                UUID.randomUUID(), null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        jdbc.update("update space_memberships set active=false, ended_at=?, ended_by_user_id=?, end_reason='VOLUNTARY_EXIT' where user_id=?",
                Timestamp.from(NOW), GUEST, GUEST);
        assertThatThrownBy(() -> useCase.correct("guest@example.com", valid))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
    }

    @Test void concurrentCorrectionsAndCorrectionVersusPaymentPreserveTheFirstCommit() throws Exception {
        var id = pending();
        Callable<String> first = () -> correctionOutcome("admin@example.com", correction(id, 0, ExpenseStatus.PENDING,
                "Primeira", "151", LocalDate.of(2026, 10, 1), null, null, null, null, null, UUID.randomUUID()));
        Callable<String> second = () -> correctionOutcome("guest@example.com", correction(id, 0, ExpenseStatus.PENDING,
                "Segunda", "152", LocalDate.of(2026, 10, 2), null, null, null, null, null, UUID.randomUUID()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = executor.invokeAll(List.of(first, second));
            assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get()))
                    .containsExactlyInAnyOrder("corrected", "conflict");
        }
        assertThat(jdbc.queryForObject("select count(*) from expense_correction_events where expense_id=?", Integer.class, id)).isOne();
        assertThat(useCase.get("admin@example.com", id).version()).isOne();

        var raceId = pending();
        Callable<String> edit = () -> correctionOutcome("guest@example.com", correction(raceId, 0, ExpenseStatus.PENDING,
                "Editada", "160", LocalDate.of(2026, 10, 4), null, null, null, null, null, UUID.randomUUID()));
        Callable<String> pay = () -> {
            try { useCase.settle("admin@example.com", payment(raceId, "155", UUID.randomUUID())); return "paid"; }
            catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = executor.invokeAll(List.of(edit, pay));
            var values = List.of(outcomes.get(0).get(), outcomes.get(1).get());
            assertThat(values).contains("conflict");
            assertThat(values).anyMatch(value -> value.equals("corrected") || value.equals("paid"));
        }
        assertThat(jdbc.queryForObject("select count(*) from expense_correction_events where expense_id=?", Integer.class, raceId)
                + jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?", Integer.class, raceId)).isOne();
    }

    @Test void correctionAuditFailureRollsBackDataVersionAndIdempotencyThenRetryWorks() {
        var id = pending();
        var command = correction(id, 0, ExpenseStatus.PENDING, "Corrigida", "150",
                LocalDate.of(2026, 9, 30), null, null, null, null, null, UUID.randomUUID());
        jdbc.execute("alter table expense_correction_events add constraint test_correction_audit_failure check (changed_fields <> 'description')");
        assertThatThrownBy(() -> useCase.correct("admin@example.com", command))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(useCase.get("admin@example.com", id).description()).isEqualTo("Conta");
        assertThat(useCase.get("admin@example.com", id).version()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from expense_idempotency_requests where operation='CORRECT_EXPENSE'", Integer.class)).isZero();
        jdbc.execute("alter table expense_correction_events drop constraint test_correction_audit_failure");
        assertThat(useCase.correct("admin@example.com", command).expense().description()).isEqualTo("Corrigida");
    }

    private CorrectExpenseCommand correction(UUID id, long version, ExpenseStatus status, String description,
            String amount, LocalDate dueDate, String notes, String paidAmount, LocalDate paymentDate,
            UUID payer, String paymentNotes, UUID key) {
        return new CorrectExpenseCommand(id, version, status, description, amount, dueDate, notes,
                paidAmount, paymentDate, payer, paymentNotes, key);
    }

    private String correctionOutcome(String email, CorrectExpenseCommand command) {
        try { useCase.correct(email, command); return "corrected"; }
        catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
    }

    private AuthenticatedUserContextRepository contextRepository() {
        return normalizedEmail -> jdbc.query("""
                select u.id, u.display_name, u.normalized_email, s.id, s.name, m.role,
                       s.currency_code, s.locale, s.time_zone
                  from identity_users u
                  join space_memberships m on m.user_id = u.id and m.active = true
                  join family_spaces s on s.id = m.space_id
                 where u.normalized_email = ?
                """, (rs, row) -> new AuthenticatedUserContext(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), SpaceRole.valueOf(rs.getString(6)),
                        rs.getString(7), rs.getString(8), rs.getString(9)), normalizedEmail).stream().findFirst();
    }

    private void insertSpaceAndMembers() {
        insertSpace(SPACE, "Casa");
        insertUser(ADMIN, "Administrador", "admin@example.com", SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", "guest@example.com", SPACE, "GUEST");
        insertSpace(OTHER_SPACE, "Outra casa");
        insertUser(UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "Outro", "other@example.com", OTHER_SPACE, "ADMINISTRATOR");
    }

    private void insertSpace(UUID id, String name) {
        jdbc.update("""
                insert into family_spaces(id, name, currency_code, locale, time_zone, created_at)
                values (?, ?, 'BRL', 'pt-BR', 'America/Sao_Paulo', ?)
                """, id, name, Timestamp.from(NOW.minusSeconds(60)));
    }

    private void insertUser(UUID id, String name, String email, UUID space, String role) {
        jdbc.update("""
                insert into identity_users(id, display_name, normalized_email, password_hash, email_confirmed, created_at)
                values (?, ?, ?, '{test}senha segura 2026', true, ?)
                """, id, name, email, Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, ?, true, ?)
                """, UUID.randomUUID(), id, space, role, Timestamp.from(NOW.minusSeconds(60)));
    }

    private void insertOtherSpaceExpense() {
        var user = UUID.fromString("00000000-0000-0000-0000-000000000201");
        var date = LocalDate.of(2026, 9, 26);
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed,
                    status, due_date, reference_date, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Privada de outro espaço', 10.00, true, 'PENDING', ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), OTHER_SPACE, date, date, user, Timestamp.from(NOW));
    }
}
