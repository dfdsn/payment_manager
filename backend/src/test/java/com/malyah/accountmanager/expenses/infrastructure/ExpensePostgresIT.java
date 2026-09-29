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
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    private DriverManagerDataSource dataSource;
    private ExpenseUseCase useCase;
    private com.malyah.accountmanager.expenses.application.CategoryService categoryService;
    private com.malyah.accountmanager.expenses.application.AttachmentUseCase attachments;
    @TempDir Path attachmentRoot;

    @BeforeEach
    void reset() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(26);
        jdbc = new JdbcTemplate(dataSource);
        insertSpaceAndMembers();
        var context = new AuthenticatedUserContextService(contextRepository());
        var categories = new JdbcCategoryRepository(jdbc);
        categoryService = new com.malyah.accountmanager.expenses.application.CategoryService(categories, context,
                UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));
        var service = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID,
                Clock.fixed(NOW, ZoneOffset.UTC), new com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess(jdbc), categories);
        useCase = new TransactionalExpenseUseCase(
                service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        attachments = new FileSystemAttachmentUseCase(jdbc, context,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), attachmentRoot,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void storesAuthorizesReplaysAndRemovesPrivateAttachments() {
        var expense = useCase.create("admin@example.com", command("Comprovante", "10.00",
                ExpenseStatus.PENDING, LocalDate.of(2026, 9, 30), null, UUID.randomUUID())).expense();
        var key = UUID.randomUUID();
        var pdf = "%PDF-1.7\nsynthetic-test-only".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var uploaded = attachments.upload("guest@example.com", expense.id(), key, "comprovante.pdf", pdf);
        assertThat(attachments.upload("guest@example.com", expense.id(), key, "comprovante.pdf", pdf).id())
                .isEqualTo(uploaded.id());
        assertThat(attachments.download("admin@example.com", expense.id(), uploaded.id()).bytes()).isEqualTo(pdf);
        assertThat(attachments.list("admin@example.com", expense.id())).singleElement()
                .satisfies(item -> assertThat(item.uploadedByDisplayName()).isEqualTo("Convidado"));
        assertThatThrownBy(() -> attachments.download("other@example.com", expense.id(), uploaded.id()))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.AttachmentException.class);
        attachments.remove("admin@example.com", expense.id(), uploaded.id());
        assertThat(attachments.list("guest@example.com", expense.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from expense_attachment_audit where attachment_id=?", Integer.class, uploaded.id())).isEqualTo(2);
    }

    @Test void rejectsInvalidAttachmentContentAndEnforcesConcurrentLimit() throws Exception {
        var expense = useCase.create("admin@example.com", command("Arquivos", "20.00",
                ExpenseStatus.PENDING, LocalDate.of(2026, 9, 30), null, UUID.randomUUID())).expense();
        assertThatThrownBy(() -> attachments.upload("admin@example.com", expense.id(), UUID.randomUUID(),
                "fake.pdf", "not-a-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.AttachmentException.class);
        var png = new byte[]{(byte)137,80,78,71,13,10,26,10,1};
        for (int index=0; index<4; index++) attachments.upload("admin@example.com", expense.id(), UUID.randomUUID(), "a"+index+".png", png);
        Callable<String> upload = () -> { try { attachments.upload("guest@example.com", expense.id(), UUID.randomUUID(), UUID.randomUUID()+".png", png); return "OK"; }
            catch (com.malyah.accountmanager.expenses.application.AttachmentException e) { return e.code(); } };
        try (var executor=Executors.newFixedThreadPool(2)) {
            assertThat(executor.invokeAll(List.of(upload, upload)).stream().map(f->{try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).toList())
                    .containsExactlyInAnyOrder("OK", "ATTACHMENT_LIMIT");
        }
        assertThat(attachments.list("admin@example.com", expense.id())).hasSize(5);
    }

    @Test void managesInitialCategoriesAndPreservesReferencedExpensesAfterRenameAndArchive() {
        assertThat(categoryService.list("admin@example.com", false)).extracting(c -> c.name())
                .containsExactly("Alimentação", "Educação", "Lazer", "Moradia", "Outros", "Saúde", "Transporte");
        var category = categoryService.create("guest@example.com", "  Condomínio  ");
        var expense = useCase.create("admin@example.com", new CreateOneOffExpenseCommand("Água", "90",
                ExpenseStatus.PENDING, LocalDate.of(2026, 10, 1), null, null, UUID.randomUUID(),
                null, null, null, category.id())).expense();
        assertThat(expense.categoryName()).isEqualTo("Condomínio");
        var renamed = categoryService.rename("admin@example.com", category.id(), category.version(), "Casa");
        assertThat(useCase.get("guest@example.com", expense.id()).categoryName()).isEqualTo("Casa");
        var archived = categoryService.archive("guest@example.com", category.id(), renamed.version());
        assertThat(archived.archived()).isTrue();
        assertThat(useCase.get("admin@example.com", expense.id()).categoryName()).isEqualTo("Casa");
        assertThat(categoryService.list("admin@example.com", false)).noneMatch(c -> c.id().equals(category.id()));
        assertThat(categoryService.list("admin@example.com", true)).anyMatch(c -> c.id().equals(category.id()) && c.archived());
        assertThatThrownBy(() -> useCase.create("admin@example.com", new CreateOneOffExpenseCommand("Luz", "80",
                ExpenseStatus.PENDING, LocalDate.of(2026, 10, 2), null, null, UUID.randomUUID(),
                null, null, null, category.id())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.CategoryConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from expense_category_events where category_id=?", Integer.class, category.id())).isEqualTo(3);
    }

    @Test void rejectsDuplicateForeignAndStaleCategoryChangesIncludingConcurrentCreation() throws Exception {
        var first = categoryService.create("admin@example.com", "Pets");
        assertThatThrownBy(() -> categoryService.create("guest@example.com", " pets "))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.CategoryConflictException.class);
        assertThatThrownBy(() -> categoryService.rename("guest@example.com", first.id(), first.version() + 1, "Animais"))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.CategoryConflictException.class);
        var foreign = jdbc.queryForObject("select id from expense_categories where space_id=? limit 1", UUID.class, OTHER_SPACE);
        assertThatThrownBy(() -> categoryService.rename("admin@example.com", foreign, 0, "Inválida"))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.CategoryNotFoundException.class);
        Callable<String> create = () -> { try { categoryService.create("admin@example.com", "Impostos"); return "CREATED"; }
            catch (com.malyah.accountmanager.expenses.application.CategoryConflictException e) { return "CONFLICT"; } };
        try (var executor = Executors.newFixedThreadPool(2)) {
            assertThat(executor.invokeAll(List.of(create, create)).stream().map(f -> { try{return f.get();}catch(Exception e){throw new AssertionError(e);} }).toList())
                    .containsExactlyInAnyOrder("CREATED", "CONFLICT");
        }
    }

    @Test void assignsChangesAndRemovesResponsibleWhileHistoryKeepsDistinctPeopleAndPagination() {
        var created = useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Internet", "120", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 5), null, null,
                UUID.randomUUID(), null, null, null, null, GUEST)).expense();
        assertThat(created.responsibleUserId()).isEqualTo(GUEST);
        assertThat(created.responsibleDisplayName()).isEqualTo("Convidado");

        var reassigned = useCase.correct("guest@example.com", new CorrectExpenseCommand(
                created.id(), created.version(), created.status(), created.description(), created.amount(),
                created.dueDate(), created.notes(), null, null, null, null, UUID.randomUUID(), null, ADMIN)).expense();
        assertThat(reassigned.responsibleDisplayName()).isEqualTo("Administrador");

        var paid = useCase.settle("guest@example.com", new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                created.id(), reassigned.version(), "120", LocalDate.of(2026, 10, 4), GUEST,
                "Pago pelo convidado", UUID.randomUUID())).expense();
        var unassigned = useCase.correct("admin@example.com", new CorrectExpenseCommand(
                paid.id(), paid.version(), paid.status(), paid.description(), paid.amount(), paid.dueDate(), paid.notes(),
                paid.paidAmount(), paid.paymentDate(), paid.paidByUserId(), paid.paymentAudit().notes(),
                UUID.randomUUID(), null, null)).expense();
        assertThat(unassigned.responsibleUserId()).isNull();
        assertThat(unassigned.paidByDisplayName()).isEqualTo("Convidado");
        assertThat(unassigned.paymentAudit().recordedByDisplayName()).isEqualTo("Convidado");

        var firstPage = useCase.history("admin@example.com", created.id(), 0, 2);
        var secondPage = useCase.history("guest@example.com", created.id(), 1, 2);
        assertThat(firstPage.totalElements()).isEqualTo(4);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content()).extracting(event -> event.type())
                .containsExactly("EXPENSE_CREATED", "EXPENSE_CORRECTED");
        assertThat(secondPage.content()).extracting(event -> event.type())
                .containsExactly("EXPENSE_PAID", "EXPENSE_CORRECTED");
        assertThat(secondPage.content().getLast().changes()).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo("responsibleUserId");
            assertThat(change.previousValue()).isEqualTo("Administrador");
            assertThat(change.currentValue()).isNull();
        });

        var foreign = UUID.fromString("00000000-0000-0000-0000-000000000201");
        assertThatThrownBy(() -> useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Inválida", "10", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 6), null, null,
                UUID.randomUUID(), null, null, null, null, foreign)))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> useCase.history("other@example.com", created.id(), 0, 10))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseNotFoundException.class);
    }

    @Test void concurrentAssignmentAndMembershipDepartureNeverLeavesAnInactiveResponsible() throws Exception {
        var created = useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Seguro", "75", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 8), null, null,
                UUID.randomUUID(), null, null, null, null, null)).expense();
        Callable<String> assign = () -> {
            try {
                useCase.correct("admin@example.com", new CorrectExpenseCommand(
                        created.id(), created.version(), created.status(), created.description(), created.amount(),
                        created.dueDate(), created.notes(), null, null, null, null,
                        UUID.randomUUID(), null, GUEST));
                return "ASSIGNED";
            } catch (com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException
                    | com.malyah.accountmanager.expenses.application.ExpenseStateConflictException exception) {
                return "REJECTED";
            }
        };
        Callable<String> depart = () -> {
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from family_spaces where id=? for update", UUID.class, SPACE);
                new JdbcMembershipDepartureHandler(jdbc).beforeMembershipEnds(SPACE, GUEST, ADMIN, NOW);
                jdbc.update("""
                        update space_memberships
                           set active=false, ended_at=?, ended_by_user_id=?, end_reason='ADMIN_REMOVAL'
                         where space_id=? and user_id=? and active
                        """, Timestamp.from(NOW), ADMIN, SPACE, GUEST);
            });
            return "DEPARTED";
        };

        List<String> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            outcomes = executor.invokeAll(List.of(assign, depart)).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();
        }

        assertThat(outcomes).contains("DEPARTED").anyMatch(value -> value.equals("ASSIGNED") || value.equals("REJECTED"));
        assertThat(jdbc.queryForObject("select active from space_memberships where user_id=?", Boolean.class, GUEST)).isFalse();
        assertThat(jdbc.queryForObject("select responsible_user_id from expense_entries where id=?", UUID.class, created.id())).isNull();
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

    @Test void searchesFiltersDatesStatusesAndKeepsCountsInsideTheAuthorizedSpace() {
        var category=categoryService.create("admin@example.com","Serviços");
        var overdue=useCase.create("admin@example.com",new CreateOneOffExpenseCommand("Energia elétrica","100",ExpenseStatus.PENDING,
                LocalDate.of(2026,9,24),null,null,UUID.randomUUID(),null,null,null,category.id(),GUEST)).expense();
        var paid=useCase.create("guest@example.com",new CreateOneOffExpenseCommand("Energia solar","200",ExpenseStatus.PAID,
                LocalDate.of(2026,9,30),LocalDate.of(2026,10,2),null,UUID.randomUUID(),"205",GUEST,null,null,null)).expense();
        var cancelled=useCase.create("admin@example.com",command("Energia cancelada","10",ExpenseStatus.PENDING,
                LocalDate.of(2026,9,25),null,UUID.randomUUID())).expense();
        useCase.cancel("admin@example.com",new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                cancelled.id(),cancelled.version(),"Duplicada",UUID.randomUUID()));
        insertOtherSpaceExpense();
        var overdueQuery=new ExpenseListQuery(0,20,ExpenseSort.DESCRIPTION,SortDirection.ASC," energia ",
                LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),com.malyah.accountmanager.expenses.application.ExpenseDateBasis.DUE_DATE,
                category.id(),false,GUEST,false,null,com.malyah.accountmanager.expenses.application.ExpenseStatusFilter.OVERDUE,null);
        assertThat(useCase.list("guest@example.com",overdueQuery).content()).extracting(e->e.id()).containsExactly(overdue.id());
        var paymentQuery=new ExpenseListQuery(0,20,ExpenseSort.REFERENCE_DATE,SortDirection.ASC,"energia",
                LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),com.malyah.accountmanager.expenses.application.ExpenseDateBasis.PAYMENT_DATE,
                null,true,null,true,GUEST,com.malyah.accountmanager.expenses.application.ExpenseStatusFilter.PAID,null);
        var paymentPage=useCase.list("admin@example.com",paymentQuery);
        assertThat(paymentPage.totalElements()).isEqualTo(1);
        assertThat(paymentPage.content()).extracting(e->e.id()).containsExactly(paid.id());
        jdbc.update("""
                update space_memberships
                   set active=false, ended_at=?, ended_by_user_id=?, end_reason='ADMIN_REMOVAL'
                 where user_id=?
                """, Timestamp.from(NOW), ADMIN, GUEST);
        var options=useCase.filterOptions("admin@example.com");
        assertThat(options.responsiblePeople()).anySatisfy(person -> {
            assertThat(person.userId()).isEqualTo(GUEST);
            assertThat(person.activeMember()).isFalse();
        });
        assertThat(options.payerPeople()).anySatisfy(person -> {
            assertThat(person.userId()).isEqualTo(GUEST);
            assertThat(person.activeMember()).isFalse();
        });
        var cancelledQuery=new ExpenseListQuery(0,20,ExpenseSort.DESCRIPTION,SortDirection.ASC,null,
                LocalDate.of(2026,9,25),LocalDate.of(2026,9,25),com.malyah.accountmanager.expenses.application.ExpenseDateBasis.DUE_DATE,
                null,false,null,false,null,com.malyah.accountmanager.expenses.application.ExpenseStatusFilter.CANCELLED,null);
        assertThat(useCase.list("admin@example.com",cancelledQuery).content()).extracting(e->e.id()).containsExactly(cancelled.id());
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
                "B", "20.00", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 27), null, key));
        assertThatThrownBy(() -> useCase.create("admin@example.com", command(
                "Alterada", "20.00", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 27), null, key)))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);
        useCase.create("admin@example.com", command(
                "A", "10.00", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 26), null, UUID.randomUUID()));
        useCase.create("admin@example.com", command(
                "C", "30.00", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 28), null, UUID.randomUUID()));

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

    @Test void reversesPaymentPreservesEvidenceAndAllowsASeparateNewPayment() {
        var id = pending();
        var paid = useCase.settle("admin@example.com", payment(id, "155", UUID.randomUUID())).expense();
        var key = UUID.randomUUID();
        var reverse = new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                id, paid.version(), "Cobrança paga pelo meio incorreto", key);

        var reversed = useCase.reversePayment("guest@example.com", reverse);
        assertThat(reversed.expense().status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(reversed.expense().paidAmount()).isNull();
        assertThat(reversed.expense().paymentDate()).isNull();
        assertThat(reversed.expense().overdue()).isFalse();
        assertThat(reversed.expense().version()).isEqualTo(2);
        assertThat(useCase.reversePayment("guest@example.com", reverse).replayed()).isTrue();
        assertThatThrownBy(() -> useCase.reversePayment("guest@example.com",
                new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                        id, paid.version(), "Outro motivo", key)))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);

        var historyAfterReversal = useCase.get("admin@example.com", id).history();
        assertThat(historyAfterReversal).extracting(event -> event.type())
                .containsExactly("EXPENSE_CREATED", "EXPENSE_PAID", "PAYMENT_REVERSED");
        assertThat(historyAfterReversal.get(2).reason()).isEqualTo("Cobrança paga pelo meio incorreto");
        assertThat(historyAfterReversal.get(2).paidAmount()).isEqualTo("155.00");
        assertThat(historyAfterReversal.get(2).payerUserId()).isEqualTo(GUEST);

        var repaid = useCase.settle("guest@example.com",
                new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                        id, 2, "150", LocalDate.of(2026, 10, 2), ADMIN, "Nova quitação", UUID.randomUUID())).expense();
        assertThat(repaid.status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(repaid.paidByUserId()).isEqualTo(ADMIN);
        assertThat(useCase.get("admin@example.com", id).history()).extracting(event -> event.type())
                .containsExactly("EXPENSE_CREATED", "EXPENSE_PAID", "PAYMENT_REVERSED", "EXPENSE_PAID");
    }

    @Test void cancellationIsHistoricalHiddenFromActiveListAndRejectsIncompatibleStates() {
        var id = pending();
        var key = UUID.randomUUID();
        var cancel = new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                id, 0, "Lançamento duplicado", key);
        var cancelled = useCase.cancel("guest@example.com", cancel);

        assertThat(cancelled.expense().status()).isEqualTo(ExpenseStatus.CANCELLED);
        assertThat(cancelled.expense().version()).isOne();
        assertThat(useCase.cancel("guest@example.com", cancel).replayed()).isTrue();
        assertThatThrownBy(() -> useCase.cancel("guest@example.com",
                new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        id, 0, "Outro motivo", key)))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);
        assertThat(useCase.list("admin@example.com",
                new ExpenseListQuery(0, 20, ExpenseSort.REFERENCE_DATE, SortDirection.ASC)).content()).isEmpty();
        assertThat(useCase.get("admin@example.com", id).history()).filteredOn(event -> event.type().equals("EXPENSE_CANCELLED")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("EXPENSE_CANCELLED");
            assertThat(event.actorUserId()).isEqualTo(GUEST);
            assertThat(event.reason()).isEqualTo("Lançamento duplicado");
        });
        assertThatThrownBy(() -> useCase.settle("admin@example.com",
                new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                        id, 1, "150", LocalDate.now(), ADMIN, null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);
        assertThatThrownBy(() -> useCase.correct("admin@example.com", correction(id, 1, ExpenseStatus.PENDING,
                "Conta", "150", LocalDate.of(2026, 9, 30), null, null, null, null, null, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);

        var paidId = pending();
        var paid = useCase.settle("admin@example.com", payment(paidId, "150", UUID.randomUUID())).expense();
        assertThatThrownBy(() -> useCase.cancel("admin@example.com",
                new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        paidId, paid.version(), "Cancelar paga", UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class);
    }

    @Test void staleAndForeignActionsFailAndConcurrentCancelVersusPaymentHasOneWinner() throws Exception {
        var foreignId = pending();
        assertThatThrownBy(() -> useCase.cancel("other@example.com",
                new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        foreignId, 0, "Sem acesso", UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseNotFoundException.class);

        var id = pending();
        Callable<String> cancel = () -> {
            try {
                useCase.cancel("guest@example.com", new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        id, 0, "Não é mais necessária", UUID.randomUUID()));
                return "cancelled";
            } catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) {
                return "conflict";
            }
        };
        Callable<String> pay = () -> {
            try { useCase.settle("admin@example.com", payment(id, "150", UUID.randomUUID())); return "paid"; }
            catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(cancel, pay));
            var outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertThat(outcomes).contains("conflict");
            assertThat(outcomes).anyMatch(value -> value.equals("cancelled") || value.equals("paid"));
        }
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where expense_id=?", Integer.class, id)
                + jdbc.queryForObject("select count(*) from expense_cancellation_events where expense_id=?", Integer.class, id)).isOne();

        var editRaceId = pending();
        Callable<String> cancelAgainstEdit = () -> {
            try {
                useCase.cancel("admin@example.com", new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        editRaceId, 0, "Cancelamento concorrente", UUID.randomUUID()));
                return "cancelled";
            } catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
        };
        Callable<String> editAgainstCancel = () -> correctionOutcome("guest@example.com", correction(
                editRaceId, 0, ExpenseStatus.PENDING, "Editada", "151", LocalDate.of(2026, 10, 1),
                null, null, null, null, null, UUID.randomUUID()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(cancelAgainstEdit, editAgainstCancel));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).contains("conflict");
        }

        var reverseRaceId = pending();
        var activePayment = useCase.settle("admin@example.com", payment(reverseRaceId, "150", UUID.randomUUID())).expense();
        Callable<String> reverseAgainstEdit = () -> {
            try {
                useCase.reversePayment("admin@example.com", new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                        reverseRaceId, activePayment.version(), "Reversão concorrente", UUID.randomUUID()));
                return "reversed";
            } catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) { return "conflict"; }
        };
        Callable<String> editAgainstReverse = () -> correctionOutcome("guest@example.com", correction(
                reverseRaceId, activePayment.version(), ExpenseStatus.PAID, "Quitada editada", "150",
                LocalDate.of(2026, 9, 30), null, "150", LocalDate.of(2026, 10, 1), GUEST,
                "Pagamento integral", UUID.randomUUID()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(reverseAgainstEdit, editAgainstReverse));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).contains("conflict");
        }
    }

    @Test void reversalAndCancellationAuditFailuresRollBackStateVersionAndIdempotency() {
        var paidId = pending();
        var paid = useCase.settle("admin@example.com", payment(paidId, "150", UUID.randomUUID())).expense();
        var reverse = new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                paidId, paid.version(), "Falha controlada", UUID.randomUUID());
        jdbc.execute("alter table expense_payment_events add constraint test_reversal_failure check (event_type <> 'PAYMENT_REVERSED')");
        assertThatThrownBy(() -> useCase.reversePayment("admin@example.com", reverse))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(useCase.get("admin@example.com", paidId).status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(useCase.get("admin@example.com", paidId).version()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from expense_idempotency_requests where operation='REVERSE_PAYMENT'", Integer.class)).isZero();
        jdbc.execute("alter table expense_payment_events drop constraint test_reversal_failure");

        var pendingId = pending();
        var cancellation = new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                pendingId, 0, "Falha controlada", UUID.randomUUID());
        jdbc.execute("alter table expense_cancellation_events add constraint test_cancellation_failure check (reason <> 'Falha controlada')");
        assertThatThrownBy(() -> useCase.cancel("admin@example.com", cancellation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(useCase.get("admin@example.com", pendingId).status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(useCase.get("admin@example.com", pendingId).version()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from expense_idempotency_requests where operation='CANCEL_EXPENSE'", Integer.class)).isZero();
    }

    @Test void paidWithoutDueDateMustBeCorrectedWithDueDateBeforeReversal() {
        var paid = useCase.create("admin@example.com", new CreateOneOffExpenseCommand(
                "Compra sem vencimento", "80", ExpenseStatus.PAID, null, LocalDate.of(2026, 9, 25), null,
                UUID.randomUUID(), "80", GUEST, null)).expense();
        assertThatThrownBy(() -> useCase.reversePayment("admin@example.com",
                new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                        paid.id(), paid.version(), "Pagamento incorreto", UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException.class)
                .hasMessageContaining("vencimento");
        assertThat(jdbc.queryForObject(
                "select count(*) from expense_idempotency_requests where operation='REVERSE_PAYMENT'", Integer.class))
                .isZero();
        assertThat(useCase.get("admin@example.com", paid.id()).status()).isEqualTo(ExpenseStatus.PAID);

        var corrected = useCase.correct("admin@example.com", correction(
                paid.id(), paid.version(), ExpenseStatus.PAID, paid.description(), paid.amount(),
                LocalDate.of(2026, 9, 24), paid.notes(), paid.paidAmount(), paid.paymentDate(),
                paid.paidByUserId(), paid.paymentAudit().notes(), UUID.randomUUID())).expense();
        var reversed = useCase.reversePayment("admin@example.com",
                new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                        paid.id(), corrected.version(), "Pagamento incorreto", UUID.randomUUID())).expense();

        assertThat(reversed.status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(reversed.dueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(reversed.overdue()).isTrue();
        assertThat(useCase.get("admin@example.com", paid.id()).history()).extracting(event -> event.type())
                .containsExactly("EXPENSE_CREATED", "EXPENSE_PAID", "EXPENSE_CORRECTED", "PAYMENT_REVERSED");
    }

    @Test void atomicBatchSettlesEveryItemWithChargeAmountPayerActorAndCorrelation() {
        var first = pending();
        var second = useCase.create("guest@example.com", command("Mercado", "25.50", ExpenseStatus.PENDING,
                LocalDate.of(2026, 10, 2), null, UUID.randomUUID())).expense().id();
        var key = UUID.randomUUID();
        var command = batch(key, new com.malyah.accountmanager.expenses.application.BatchSettlementItem(first, 0),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(second, 0));

        var result = useCase.settleBatch("admin@example.com", command);

        assertThat(result.replayed()).isFalse();
        assertThat(result.items()).extracting(item -> item.paidAmount()).containsExactlyInAnyOrder("150.00", "25.50");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.fromVersion()).isZero();
            assertThat(item.toVersion()).isOne();
        });
        assertThat(useCase.get("guest@example.com", first).status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(useCase.get("guest@example.com", second).paidByUserId()).isEqualTo(GUEST);
        assertThat(useCase.get("guest@example.com", second).paymentAudit().recordedByUserId()).isEqualTo(ADMIN);
        assertThat(jdbc.queryForList("select distinct batch_operation_id from expense_payment_events where expense_id in (?, ?)",
                UUID.class, first, second)).containsExactly(result.operationId());
        assertThat(useCase.get("admin@example.com", first).history())
                .filteredOn(event -> event.type().equals("EXPENSE_PAID")).singleElement()
                .satisfies(event -> assertThat(event.batchOperationId()).isEqualTo(result.operationId()));

        var replay = useCase.settleBatch("admin@example.com", command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operationId()).isEqualTo(result.operationId());
        assertThat(replay.items()).isEqualTo(result.items());
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events where batch_operation_id=?",
                Integer.class, result.operationId())).isEqualTo(2);
        assertThatThrownBy(() -> useCase.settleBatch("admin@example.com",
                new com.malyah.accountmanager.expenses.application.BatchSettlementCommand(command.items(),
                        LocalDate.of(2026, 10, 2), GUEST, true, key)))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);
    }

    @Test void onePaidCancelledStaleOrForeignItemRejectsTheWholeBatch() {
        var paid = pending();
        useCase.settle("admin@example.com", payment(paid, "150", UUID.randomUUID()));
        assertBatchRejectedWithoutChangingCompanion(paid, 1, "STATE_INCOMPATIBLE");

        var cancelled = pending();
        useCase.cancel("admin@example.com", new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                cancelled, 0, "Duplicada", UUID.randomUUID()));
        assertBatchRejectedWithoutChangingCompanion(cancelled, 1, "STATE_INCOMPATIBLE");

        var stale = pending();
        jdbc.update("update expense_entries set version=1 where id=?", stale);
        assertBatchRejectedWithoutChangingCompanion(stale, 0, "VERSION_CONFLICT");

        var foreign = insertOtherSpaceExpense();
        assertBatchRejectedWithoutChangingCompanion(foreign, 0, "UNAVAILABLE");
    }

    @Test void inactiveActorOrPayerCannotRunBatchAndNoOperationIsClaimed() {
        var id = pending();
        jdbc.update("update space_memberships set active=false, ended_at=?, ended_by_user_id=?, end_reason='VOLUNTARY_EXIT' where user_id=?",
                Timestamp.from(NOW), GUEST, GUEST);
        assertThatThrownBy(() -> useCase.settleBatch("admin@example.com", batch(UUID.randomUUID(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(id, 0))))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> useCase.settleBatch("guest@example.com", new com.malyah.accountmanager.expenses.application.BatchSettlementCommand(
                List.of(new com.malyah.accountmanager.expenses.application.BatchSettlementItem(id, 0)),
                LocalDate.of(2026, 10, 1), ADMIN, true, UUID.randomUUID())))
                .isInstanceOf(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from expense_batch_payment_operations", Integer.class)).isZero();
        assertThat(useCase.get("admin@example.com", id).status()).isEqualTo(ExpenseStatus.PENDING);
    }

    @Test void overlappingConcurrentBatchesHaveOneWinnerAndTheLoserHasNoPartialPayment() throws Exception {
        var shared = pending();
        var firstOnly = pending();
        var secondOnly = pending();
        Callable<String> first = () -> batchOutcome(batch(UUID.randomUUID(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(shared, 0),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(firstOnly, 0)));
        Callable<String> second = () -> batchOutcome(batch(UUID.randomUUID(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(shared, 0),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(secondOnly, 0)));
        List<String> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(first, second));
            outcomes = List.of(futures.get(0).get(), futures.get(1).get());
        }
        assertThat(outcomes).containsExactlyInAnyOrder("paid", "conflict");
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events", Integer.class)).isEqualTo(2);
        assertThat(List.of(useCase.get("admin@example.com", firstOnly).status(),
                useCase.get("admin@example.com", secondOnly).status()))
                .containsExactlyInAnyOrder(ExpenseStatus.PAID, ExpenseStatus.PENDING);
    }

    @Test void batchCompetesAtomicallyWithIndividualPaymentCorrectionAndCancellation() throws Exception {
        for (String operation : List.of("payment", "correction", "cancellation")) {
            var shared = pending();
            var companion = pending();
            Callable<String> batch = () -> batchOutcome(batch(UUID.randomUUID(),
                    new com.malyah.accountmanager.expenses.application.BatchSettlementItem(shared, 0),
                    new com.malyah.accountmanager.expenses.application.BatchSettlementItem(companion, 0)));
            Callable<String> individual = () -> individualOutcome(operation, shared);
            List<String> outcomes;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var futures = executor.invokeAll(List.of(batch, individual));
                outcomes = List.of(futures.get(0).get(), futures.get(1).get());
            }
            assertThat(outcomes).contains("conflict");
            if (outcomes.getFirst().equals("conflict"))
                assertThat(useCase.get("admin@example.com", companion).status()).isEqualTo(ExpenseStatus.PENDING);
        }
    }

    @Test void auditFailureRollsBackEntireBatchIncludingOperationAndAllPayments() {
        var first = pending();
        var second = pending();
        var command = batch(UUID.randomUUID(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(first, 0),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(second, 0));
        jdbc.execute("alter table expense_payment_events add constraint test_batch_audit_failure check (expense_id <> '" + second + "')");

        assertThatThrownBy(() -> useCase.settleBatch("admin@example.com", command))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(useCase.get("admin@example.com", first).status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(useCase.get("admin@example.com", second).status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from expense_payment_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from expense_batch_payment_operations", Integer.class)).isZero();
        jdbc.execute("alter table expense_payment_events drop constraint test_batch_audit_failure");
        assertThat(useCase.settleBatch("admin@example.com", command).items()).hasSize(2);
    }

    private CorrectExpenseCommand correction(UUID id, long version, ExpenseStatus status, String description,
            String amount, LocalDate dueDate, String notes, String paidAmount, LocalDate paymentDate,
            UUID payer, String paymentNotes, UUID key) {
        return new CorrectExpenseCommand(id, version, status, description, amount, dueDate, notes,
                paidAmount, paymentDate, payer, paymentNotes, key);
    }

    private com.malyah.accountmanager.expenses.application.BatchSettlementCommand batch(
            UUID key, com.malyah.accountmanager.expenses.application.BatchSettlementItem... items) {
        return new com.malyah.accountmanager.expenses.application.BatchSettlementCommand(
                List.of(items), LocalDate.of(2026, 10, 1), GUEST, true, key);
    }

    private void assertBatchRejectedWithoutChangingCompanion(UUID invalidId, long version, String code) {
        var companion = pending();
        assertThatThrownBy(() -> useCase.settleBatch("admin@example.com", batch(UUID.randomUUID(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(companion, 0),
                new com.malyah.accountmanager.expenses.application.BatchSettlementItem(invalidId, version))))
                .isInstanceOf(com.malyah.accountmanager.expenses.application.BatchSettlementConflictException.class)
                .satisfies(error -> assertThat(((com.malyah.accountmanager.expenses.application.BatchSettlementConflictException) error)
                        .problems()).extracting(problem -> problem.code()).contains(code));
        assertThat(useCase.get("admin@example.com", companion).status()).isEqualTo(ExpenseStatus.PENDING);
    }

    private String batchOutcome(com.malyah.accountmanager.expenses.application.BatchSettlementCommand command) {
        try { useCase.settleBatch("admin@example.com", command); return "paid"; }
        catch (com.malyah.accountmanager.expenses.application.BatchSettlementConflictException
                | com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) {
            return "conflict";
        }
    }

    private String individualOutcome(String operation, UUID id) {
        try {
            switch (operation) {
                case "payment" -> useCase.settle("guest@example.com", payment(id, "150", UUID.randomUUID()));
                case "correction" -> useCase.correct("guest@example.com", correction(id, 0, ExpenseStatus.PENDING,
                        "Editada", "151", LocalDate.of(2026, 10, 2), null, null, null, null, null,
                        UUID.randomUUID()));
                case "cancellation" -> useCase.cancel("guest@example.com",
                        new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                                id, 0, "Cancelamento concorrente", UUID.randomUUID()));
                default -> throw new IllegalArgumentException(operation);
            }
            return operation;
        } catch (com.malyah.accountmanager.expenses.application.ExpenseStateConflictException conflict) {
            return "conflict";
        }
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

    private UUID insertOtherSpaceExpense() {
        var user = UUID.fromString("00000000-0000-0000-0000-000000000201");
        var date = LocalDate.of(2026, 9, 26);
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed,
                    status, due_date, reference_date, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Privada de outro espaço', 10.00, true, 'PENDING', ?, ?, ?, ?, 0)
                """, id, OTHER_SPACE, date, date, user, Timestamp.from(NOW));
        return id;
    }
}
