package com.malyah.accountmanager.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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

import com.malyah.accountmanager.expenses.application.CancelExpenseCommand;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.infrastructure.IdentityTestFixtures;
import com.malyah.accountmanager.notifications.application.MemberNotificationNotFoundException;
import com.malyah.accountmanager.notifications.application.MemberNotificationService;
import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.MemberNotificationView;
import com.malyah.accountmanager.notifications.application.NotificationQueryValidationException;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;
import com.malyah.accountmanager.expenses.application.ReversePaymentCommand;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReminderQueries;
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
import com.malyah.accountmanager.installments.infrastructure.InstallmentTestFixtures;
import com.malyah.accountmanager.notifications.application.ReminderQueryValidationException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryService;
import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryView;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.recurrences.application.CreateRecurrenceCommand;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastCatalog;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;
import com.malyah.accountmanager.recurrences.infrastructure.JdbcRecurrenceGenerationJob;
import com.malyah.accountmanager.recurrences.infrastructure.RecurrenceTestFixtures;

/**
 * H08.3 against real PostgreSQL: summaries generated by the H08.2 job with a controllable clock, bills handled by their
 * own use cases, memberships changed by the H01.4 use case. Every expected value comes from the matrix N1–N14 in
 * docs/evidencias/H08.3.md. "Today" is 05/10/2026 in São Paulo: first slot 12:00 UTC, second 21:00 UTC. Tests named
 * "simulated" drive the failure contract of H08.4 directly; no provider is involved.
 */
@Testcontainers
class MemberNotificationPostgresIT {
    static final Instant FIRST = Instant.parse("2026-10-05T12:00:00Z");
    static final Instant SECOND = Instant.parse("2026-10-05T21:00:00Z");
    static final Instant NEXT_FIRST = Instant.parse("2026-10-06T12:00:00Z");
    static final UUID SPACE = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("c0000000-0000-0000-0000-000000000003");
    static final UUID OUTSIDER = UUID.fromString("d0000000-0000-0000-0000-000000000002");
    static final String A = "admin@example.com";
    static final String G = "guest@example.com";
    static final String O = "other@example.com";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_notifications_test").withUsername("account_manager")
            .withPassword("test-only-password");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private MutableClock clock;
    private AuthenticatedUserContextService context;
    private ExpenseService expenses;
    private ReminderSettingsService settingsService;
    private ReminderSettingsUseCase settings;
    private ReminderSummaryJob job;
    private ReminderSummaryUseCase summaries;
    private MemberNotificationService notificationService;
    private MemberNotificationUseCase inbox;
    private MembershipManagementUseCase memberships;
    private JdbcMemberNotificationRepository repository;

    @BeforeEach
    void reset() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(27);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        clock = new MutableClock(FIRST.minusSeconds(3600));
        context = new AuthenticatedUserContextService(contextRepository());
        var members = new JdbcFinancialMemberAccess(jdbc);
        var categories = new JdbcCategoryRepository(jdbc);
        expenses = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID, clock, members,
                categories);
        var materializer = new JdbcRecurringExpenseMaterializer(jdbc, tx);
        var generation = new JdbcRecurrenceGenerationJob(jdbc, tx, materializer, clock, Duration.ofMinutes(2), 25);
        // The real adapter of this version: the provider is not implemented, so an enabled channel cannot send.
        WhatsAppProviderStatus provider = new MetaWhatsAppProvider(MetaWhatsAppProperties.disabled());
        settingsService = new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbc), context, members,
                provider, clock, UUID::randomUUID);
        settings = new TransactionalReminderSettingsUseCase(settingsService, tx);
        repository = new JdbcMemberNotificationRepository(jdbc);
        var service = new ReminderSummaryService(new JdbcReminderSummaryRepository(jdbc), repository,
                new JdbcReminderSettingsRepository(jdbc), new JdbcExpenseReminderQueries(jdbc),
                new RecurrenceForecastCatalog(RecurrenceTestFixtures.service(jdbc, context, categories, members,
                        clock, materializer, null)), generation, provider, context, clock, UUID::randomUUID,
                "https://contas.malyah.tech");
        job = new ReminderSummaryJob(service, tx, clock);
        summaries = new TransactionalReminderSummaryUseCase(service, tx);
        notificationService = new MemberNotificationService(repository, context, clock, "https://contas.malyah.tech");
        inbox = new TransactionalMemberNotificationUseCase(notificationService, tx);
        memberships = IdentityTestFixtures.memberships(jdbc, tx, clock, List.of(),
                List.of(settingsService::afterAdministrationTransferred));
    }

    /** N1: one summary notification per active member, unread, with the historical head of the summary. */
    @Test
    void n1BothMembersReceiveTheSummaryInTheApplication() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        for (var email : List.of(A, G)) {
            var page = inbox.list(email, null, null, null);
            assertThat(page.totalItems()).isOne();
            assertThat(page.unreadCount()).isOne();
            var notice = page.items().getFirst();
            assertThat(notice.type()).isEqualTo("REMINDER_SUMMARY");
            assertThat(notice.readAt()).isNull();
            assertThat(notice.title()).isEqualTo("Contas a pagar: primeiro horário de 05/10, 09:00");
            assertThat(notice.message()).isEqualTo("1 conta, total R$ 10,00");
            assertThat(notice.summary().date()).isEqualTo(d(10, 5));
            assertThat(notice.summary().count()).isOne();
            assertThat(notice.summary().total()).isEqualTo("10.00");
            assertThat(notice.summary().link()).startsWith("https://contas.malyah.tech/lembretes/resumos/");
            assertThat(notice.failure()).isNull();
        }
        assertThat(recipients("REMINDER_SUMMARY")).containsExactlyInAnyOrder(ADMIN, GUEST);
        assertThat(inbox.list(O, null, null, null).totalItems()).isZero();
    }

    /** N2: in-app always; only an enabled channel the provider cannot serve is a failure, for the administrator only. */
    @Test
    void n2InAppNeverDependsOnWhatsAppAndFailuresOnlyReachTheAdministrator() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        assertThat(count("member_notifications")).isEqualTo(2);
        clock.set(FIRST.plusSeconds(1800));
        enableWhatsApp(false);
        at(SECOND);
        assertThat(count("member_notifications")).isEqualTo(4);
        clock.set(SECOND.plusSeconds(1800));
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(2L, true, UUID.randomUUID())));
        at(NEXT_FIRST);
        assertThat(count("member_notifications")).isEqualTo(7);
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
        var admin = inbox.list(A, null, null, null);
        assertThat(admin.totalItems()).isEqualTo(4);
        var failure = admin.items().stream().filter(n -> n.failure() != null).findFirst().orElseThrow();
        assertThat(failure.type()).isEqualTo("WHATSAPP_DELIVERY_FAILURE");
        assertThat(failure.failure().code()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(failure.message()).isEqualTo(WhatsAppFailureReason.PROVIDER_UNAVAILABLE.message());
        assertThat(failure.title()).isEqualTo("WhatsApp não enviado: resumo de 06/10, 09:00");
        var guest = inbox.list(G, null, null, null);
        assertThat(guest.totalItems()).isEqualTo(3);
        assertThat(guest.items()).allSatisfy(n -> assertThat(n.failure()).isNull());
        assertThatThrownBy(() -> inbox.read(G, failure.id())).isInstanceOf(MemberNotificationNotFoundException.class);
        assertThat(jdbc.queryForObject("select read_at from member_notifications where id = ?", Timestamp.class,
                failure.id())).isNull();
    }

    /** N3: reading is individual and idempotent. */
    @Test
    void n3ReadIsIndividualAndKeepsTheFirstInstant() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        var notice = inbox.list(A, null, null, null).items().getFirst();
        clock.set(FIRST.plusSeconds(60));
        var read = inbox.read(A, notice.id());
        assertThat(read.readAt()).isEqualTo(FIRST.plusSeconds(60));
        clock.set(FIRST.plusSeconds(120));
        assertThat(inbox.read(A, notice.id()).readAt()).isEqualTo(FIRST.plusSeconds(60));
        assertThat(inbox.unreadCount(A).unreadCount()).isZero();
        assertThat(inbox.unreadCount(G).unreadCount()).isOne();
        assertThat(inbox.list(G, null, null, null).items().getFirst().readAt()).isNull();
    }

    /** N4: dismissing is individual, also reads, and moves the notification to the dismissed list. */
    @Test
    void n4DismissIsIndividual() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        var notice = inbox.list(G, null, null, null).items().getFirst();
        clock.set(FIRST.plusSeconds(90));
        var dismissed = inbox.dismiss(G, notice.id());
        assertThat(dismissed.dismissedAt()).isEqualTo(FIRST.plusSeconds(90));
        assertThat(dismissed.readAt()).isEqualTo(FIRST.plusSeconds(90));
        assertThat(inbox.list(G, "ACTIVE", 0, 20).totalItems()).isZero();
        assertThat(inbox.list(G, "DISMISSED", 0, 20).items()).extracting(MemberNotificationView::id)
                .containsExactly(notice.id());
        var admin = inbox.list(A, null, null, null);
        assertThat(admin.totalItems()).isOne();
        assertThat(admin.items().getFirst().readAt()).isNull();
        assertThat(admin.items().getFirst().dismissedAt()).isNull();
        clock.set(FIRST.plusSeconds(200));
        assertThat(inbox.dismiss(G, notice.id()).dismissedAt()).isEqualTo(FIRST.plusSeconds(90));
    }

    /** N5 + N6: reading and dismissing neither pay nor stop the next reminders; the old notice stays historical. */
    @Test
    void n5n6ReadingAndDismissingNeverPayNorStopReminders() {
        var bill = pending("Hoje", "10.00", d(10, 5));
        var before = expenses.get(A, bill);
        at(FIRST);
        for (var email : List.of(A, G)) {
            var id = inbox.list(email, null, null, null).items().getFirst().id();
            inbox.read(email, id);
            inbox.dismiss(email, id);
        }
        var after = expenses.get(A, bill);
        assertThat(after.status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(after.version()).isEqualTo(before.version());
        at(SECOND);
        for (var email : List.of(A, G)) {
            var active = inbox.list(email, null, null, null);
            assertThat(active.items()).singleElement().satisfies(n -> {
                assertThat(n.summary().slot()).isEqualTo("SECOND");
                assertThat(n.readAt()).isNull();
            });
        }
        clock.set(SECOND.plusSeconds(600));
        settle(bill);
        var old = inbox.list(G, "DISMISSED", 0, 20).items().getFirst();
        assertThat(old.summary().count()).isOne();
        assertThat(old.summary().total()).isEqualTo("10.00");
        var summary = summaries.summary(G, old.summary().id());
        assertThat(summary.items().getFirst().amount()).isEqualTo("10.00");
        assertThat(summary.items().getFirst().currentStatus()).isEqualTo("PAID");
        at(NEXT_FIRST);
        assertThat(inbox.list(G, null, null, null).totalItems()).isOne();
        assertThat(count("reminder_summaries")).isEqualTo(2);
    }

    /** N7: members and spaces are isolated; someone else's notification is not found and stays untouched. */
    @Test
    void n7IsolationBetweenMembersAndSpaces() {
        pending("Hoje", "10.00", d(10, 5));
        tx.execute(s -> expenses.create(O, new CreateOneOffExpenseCommand("Deles", "99.00", ExpenseStatus.PENDING,
                d(10, 5), null, null, UUID.randomUUID(), null, null, null, null, null)));
        at(FIRST);
        var admins = inbox.list(A, null, null, null).items().getFirst();
        var theirs = inbox.list(O, null, null, null).items().getFirst();
        assertThat(theirs.summary().total()).isEqualTo("99.00");
        assertThat(inbox.list(A, null, null, null).items()).extracting(n -> n.summary().total()).containsExactly("10.00");
        for (var attempt : List.<Runnable>of(() -> inbox.read(G, admins.id()), () -> inbox.dismiss(G, admins.id()),
                () -> inbox.read(O, admins.id()), () -> inbox.dismiss(A, theirs.id()), () -> inbox.read(A, UUID.randomUUID())))
            assertThatThrownBy(attempt::run).isInstanceOf(MemberNotificationNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from member_notifications where read_at is not null",
                Long.class)).isZero();
    }

    /** N8: a removed member is refused everywhere and receives nothing afterwards. */
    @Test
    void n8RemovedMemberIsBlocked() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        var notice = inbox.list(G, null, null, null).items().getFirst();
        clock.set(FIRST.plusSeconds(600));
        memberships.remove(A, GUEST);
        for (var attempt : List.<Runnable>of(() -> inbox.list(G, null, null, null), () -> inbox.unreadCount(G),
                () -> inbox.read(G, notice.id()), () -> inbox.dismiss(G, notice.id())))
            assertThatThrownBy(attempt::run).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        at(SECOND);
        assertThat(jdbc.queryForObject("""
                select count(*) from member_notifications n join reminder_summaries s on s.id = n.summary_id
                 where s.slot = 'SECOND' and n.recipient_user_id = ?""", Long.class, GUEST)).isZero();
        assertThat(inbox.list(A, null, null, null).totalItems()).isEqualTo(2);
    }

    /** N9: concurrent and repeated processing never duplicates; concurrent read and dismiss end consistent. */
    @Test
    void n9ReexecutionAndConcurrencyNeverDuplicate() throws Exception {
        pending("Hoje", "10.00", d(10, 5));
        enableWhatsApp(true);
        clock.set(FIRST);
        race(() -> { job.poll(); return null; }, () -> { job.poll(); return null; }, () -> { job.poll(); return null; });
        job.poll();
        assertThat(count("reminder_summaries")).isOne();
        assertThat(recipients("REMINDER_SUMMARY")).containsExactlyInAnyOrder(ADMIN, GUEST);
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
        var summaryId = jdbc.queryForObject("select id from reminder_summaries", UUID.class);
        assertThat((Integer) tx.execute(s -> repository.deliverSummary(SPACE, summaryId, FIRST.plusSeconds(5)))).isZero();
        assertThat(count("member_notifications")).isEqualTo(3);
        var notice = inbox.list(G, null, null, null).items().getFirst();
        race(() -> { inbox.read(G, notice.id()); return null; }, () -> { inbox.dismiss(G, notice.id()); return null; },
                () -> { inbox.read(G, notice.id()); return null; }, () -> { inbox.dismiss(G, notice.id()); return null; });
        var dismissed = inbox.list(G, "DISMISSED", 0, 20).items();
        assertThat(dismissed).singleElement().satisfies(n -> {
            assertThat(n.readAt()).isNotNull();
            assertThat(n.dismissedAt()).isNotNull();
        });
        assertThat(inbox.list(A, null, null, null).items()).allSatisfy(n -> assertThat(n.readAt()).isNull());
    }

    /** N10: newest first with a stable tie-break, server-side pages, validated parameters and an empty inbox. */
    @Test
    void n10OrderPaginationAndEmptyState() {
        assertThat(inbox.list(G, null, null, null)).satisfies(page -> {
            assertThat(page.items()).isEmpty();
            assertThat(page.totalItems()).isZero();
            assertThat(page.totalPages()).isZero();
            assertThat(page.unreadCount()).isZero();
        });
        var created = new java.util.ArrayList<UUID>();
        for (var day = 0; day < 25; day++)
            created.add(summary(d(10, 1).plusDays(day), FIRST.plusSeconds(day == 24 ? 23 * 60L : day * 60L)));
        var first = inbox.list(G, null, 0, 20);
        var second = inbox.list(G, null, 1, 20);
        assertThat(first.totalItems()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).hasSize(20);
        assertThat(second.items()).hasSize(5);
        var all = new java.util.ArrayList<MemberNotificationView>(first.items());
        all.addAll(second.items());
        assertThat(all).extracting(MemberNotificationView::id).doesNotHaveDuplicates();
        for (var i = 1; i < all.size(); i++) {
            var newer = all.get(i - 1);
            var older = all.get(i);
            assertThat(newer.createdAt()).isAfterOrEqualTo(older.createdAt());
            if (newer.createdAt().equals(older.createdAt()))
                assertThat(newer.id().toString()).isGreaterThan(older.id().toString());
        }
        assertThat(first.items().getFirst().createdAt()).isEqualTo(FIRST.plusSeconds(23 * 60L));
        assertThat(inbox.list(G, null, 5, 20).items()).isEmpty();
        for (var invalid : List.<Runnable>of(() -> inbox.list(G, null, 0, 0), () -> inbox.list(G, null, 0, 101),
                () -> inbox.list(G, null, -1, 20), () -> inbox.list(G, "ALL", 0, 20)))
            assertThatThrownBy(invalid::run).isInstanceOf(NotificationQueryValidationException.class);
        assertThat(inbox.list(G, null, 0, 100).items()).hasSize(25);
        assertThat(created).hasSize(25);
    }

    /** N11: after a transfer the former administrator no longer sees failures, and the new one does not inherit them. */
    @Test
    void n11AdministrativeNoticesFollowTheCurrentRole() {
        pending("Hoje", "10.00", d(10, 5));
        enableWhatsApp(true);
        at(FIRST);
        var failure = inbox.list(A, null, null, null).items().stream().filter(n -> n.failure() != null).findFirst()
                .orElseThrow();
        clock.set(FIRST.plusSeconds(600));
        memberships.transferAdministration(A, GUEST);
        var former = inbox.list(A, null, null, null);
        assertThat(former.items()).extracting(MemberNotificationView::type).containsExactly("REMINDER_SUMMARY");
        assertThat(former.unreadCount()).isOne();
        assertThatThrownBy(() -> inbox.read(A, failure.id())).isInstanceOf(MemberNotificationNotFoundException.class);
        var current = inbox.list(G, null, null, null);
        assertThat(current.items()).extracting(MemberNotificationView::type).containsExactly("REMINDER_SUMMARY");
        // The transfer revoked the consent (H08.1): the next slot is no failure for anyone.
        at(SECOND);
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
    }

    /** N12 (simulated): the H08.4 failure contract, driven directly; catalog text only, one notice per summary. */
    @Test
    void n12SimulatedFailureContractForTheWhatsAppDelivery() {
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        var summaryId = jdbc.queryForObject("select id from reminder_summaries", UUID.class);
        for (var reason : List.of(WhatsAppFailureReason.PROVIDER_REJECTED, WhatsAppFailureReason.DELIVERY_FAILED,
                WhatsAppFailureReason.RECIPIENT_INVALID, WhatsAppFailureReason.RESULT_UNCERTAIN)) {
            clock.set(clock.instant().plusSeconds(60));
            assertThat((Boolean) tx.execute(s -> notificationService.recordWhatsAppFailure(SPACE, summaryId, reason))).isTrue();
            var failures = inbox.list(A, null, null, null).items().stream().filter(n -> n.failure() != null).toList();
            assertThat(failures).singleElement().satisfies(n -> {
                assertThat(n.failure().code()).isEqualTo(reason.name());
                assertThat(n.message()).isEqualTo(reason.message());
            });
        }
        assertThat(inbox.list(A, null, null, null).items().stream().filter(n -> n.failure() != null).findFirst()
                .orElseThrow().title()).isEqualTo("WhatsApp não confirmado: resumo de 05/10, 09:00");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
        assertThatThrownBy(() -> jdbc.update("update member_notifications set failure_code = null "
                + "where type = 'WHATSAPP_DELIVERY_FAILURE'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(inbox.list(G, null, null, null).items()).allSatisfy(n -> assertThat(n.failure()).isNull());
        // A summary of another space never reaches that space's administrator.
        assertThat((Boolean) tx.execute(s -> notificationService.recordWhatsAppFailure(OTHER, summaryId,
                WhatsAppFailureReason.DELIVERY_FAILED))).isFalse();
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
    }

    /** N13 + N14: links reach the summary and the bill through the existing access control; history vs now. */
    @Test
    void n13n14LinksAndCurrentSituation() {
        var um = pending("Um", "1.00", d(10, 6));
        var dois = pending("Dois", "2.00", d(10, 7));
        pending("Hoje", "10.00", d(10, 5));
        at(FIRST);
        var notice = inbox.list(G, null, null, null).items().getFirst();
        var summaryId = notice.summary().id();
        assertThat(summaries.summary(G, summaryId)).isEqualTo(summaries.summary(A, summaryId));
        assertThatThrownBy(() -> summaries.summary(O, summaryId)).isInstanceOf(ReminderSummaryNotFoundException.class);
        assertThat(expenses.get(G, um).id()).isEqualTo(um);
        assertThatThrownBy(() -> expenses.get(O, um)).isInstanceOf(ExpenseNotFoundException.class);
        clock.set(FIRST.plusSeconds(600));
        correctDue(um, d(10, 20));
        cancel(dois);
        var view = summaries.summary(G, summaryId);
        assertThat(view.items()).extracting(i -> i.label() + " " + i.dueDate() + " " + i.currentStatus() + " "
                + i.currentDueDate()).containsExactly("Hoje 2026-10-05 PENDING 2026-10-05",
                "Um 2026-10-06 PENDING 2026-10-20", "Dois 2026-10-07 CANCELLED 2026-10-07");
        assertThat(view.total()).isEqualTo("13.00");
        assertThat(inbox.list(G, null, null, null).items().getFirst().summary().total()).isEqualTo("13.00");
    }

    private void at(Instant instant) {
        clock.set(instant);
        job.poll();
    }

    private void enableWhatsApp(boolean enable) {
        tx.execute(s -> settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, "(11) 98765-4321",
                UUID.randomUUID())));
        tx.execute(s -> settings.grantConsent(A, ReminderSettingsCommand.consent(1L, "(11) 98765-4321", true,
                UUID.randomUUID())));
        if (enable) tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(2L, true,
                UUID.randomUUID())));
    }

    private List<UUID> recipients(String type) {
        return jdbc.queryForList("select recipient_user_id from member_notifications where type = ?", UUID.class,
                type);
    }

    private UUID summary(LocalDate date, Instant at) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into reminder_summaries(id, space_id, local_date, slot, scheduled_time, time_zone, scheduled_at,
                    generated_at, item_count, total_amount, estimated_count, estimated_amount, overdue_count)
                values (?, ?, ?, 'FIRST', time '09:00', 'America/Sao_Paulo', ?, ?, 1, 10.00, 0, 0, 0)
                """, id, SPACE, date, Timestamp.from(at), Timestamp.from(at));
        tx.execute(s -> repository.deliverSummary(SPACE, id, at));
        return id;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID pending(String description, String amount, LocalDate due) {
        return tx.execute(s -> expenses.create(A, new CreateOneOffExpenseCommand(description, amount,
                ExpenseStatus.PENDING, due, null, null, UUID.randomUUID(), null, null, null, null, null))
                .expense().id());
    }

    private void settle(UUID id) {
        var current = expenses.get(A, id);
        tx.execute(s -> expenses.settle(A, new SettleExpenseCommand(id, current.version(), current.amount(),
                d(10, 5), ADMIN, null, UUID.randomUUID())));
    }

    private void cancel(UUID id) {
        tx.execute(s -> expenses.cancel(A, new CancelExpenseCommand(id, expenses.get(A, id).version(), "Não será cobrada",
                UUID.randomUUID())));
    }

    private void correctDue(UUID id, LocalDate due) {
        var e = expenses.get(A, id);
        tx.execute(s -> expenses.correct(A, new CorrectExpenseCommand(id, e.version(), e.status(), e.description(),
                e.amount(), due, e.notes(), null, null, null, null, UUID.randomUUID(), e.categoryId(),
                e.responsibleUserId())));
    }

    @SafeVarargs
    private static void race(Callable<Void>... tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.length)) {
            var futures = java.util.Arrays.stream(tasks).map(task -> pool.submit(() -> {
                start.await();
                return task.call();
            })).toList();
            start.countDown();
            for (var future : futures) future.get();
        }
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
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
                + "(?,?,'BRL','pt-BR','America/Sao_Paulo',?)", id, name, Timestamp.from(FIRST.minusSeconds(864000)));
    }

    private void insertUser(UUID id, String name, String email, UUID space, String role) {
        jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,"
                + "created_at) values (?,?,?,'{test}x',true,?)", id, name, email, Timestamp.from(FIRST.minusSeconds(864000)));
        jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values "
                + "(?,?,?,?,true,?)", UUID.randomUUID(), id, space, role, Timestamp.from(FIRST.minusSeconds(864000)));
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
