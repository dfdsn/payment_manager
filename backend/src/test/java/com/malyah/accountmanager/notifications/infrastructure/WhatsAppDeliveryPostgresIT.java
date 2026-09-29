package com.malyah.accountmanager.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.malyah.accountmanager.expenses.application.CancelExpenseCommand;
import com.malyah.accountmanager.expenses.application.CorrectExpenseCommand;
import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.SettleExpenseCommand;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseReminderQueries;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.IdentityTestFixtures;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.notifications.application.MemberNotificationService;
import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.NotificationAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryService;
import com.malyah.accountmanager.notifications.application.WhatsAppAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppTestUnavailableException;
import com.malyah.accountmanager.notifications.domain.WebhookSignature;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastCatalog;
import com.malyah.accountmanager.recurrences.infrastructure.JdbcRecurrenceGenerationJob;
import com.malyah.accountmanager.recurrences.infrastructure.RecurrenceTestFixtures;

/**
 * H08.4 against real PostgreSQL and a SIMULATED Meta Cloud API ({@link FakeMetaServer}, a local HTTP server that
 * only exists in tests). The real adapter, job, webhook and use cases run unchanged; summaries come from the H08.2
 * job with a controllable clock. Every expected value comes from the matrix W1–W21 in docs/evidencias/H08.4.md.
 * Nothing here talks to Meta, so nothing here proves a real delivery. "Today" is 05/10/2026 in São Paulo: first
 * slot 12:00 UTC, second 21:00 UTC.
 */
@Testcontainers
class WhatsAppDeliveryPostgresIT {
    static final Instant FIRST = Instant.parse("2026-10-05T12:00:00Z");
    static final Instant SECOND = Instant.parse("2026-10-05T21:00:00Z");
    static final UUID SPACE = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("e0000000-0000-0000-0000-000000000003");
    static final UUID OUTSIDER = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    static final String A = "admin@example.com";
    static final String G = "guest@example.com";
    static final String O = "other@example.com";
    static final String PHONE_ID = "123456789012345";
    static final String TOKEN = "test-token-not-real";
    static final String APP_SECRET = "test-app-secret-not-real";
    static final String VERIFY = "test-verify-token-not-real";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_whatsapp_test").withUsername("account_manager")
            .withPassword("test-only-password");

    private FakeMetaServer meta;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private MutableClock clock;
    private AuthenticatedUserContextService context;
    private ExpenseService expenses;
    private ReminderSettingsService settingsService;
    private ReminderSettingsUseCase settings;
    private MembershipManagementUseCase memberships;
    private MemberNotificationUseCase inbox;
    private ReminderSummaryJob summaryJob;
    private WhatsAppDeliveryService deliveries;
    private WhatsAppDeliveryJob job;
    private MetaWhatsAppProvider sender;
    private WhatsAppDeliveryUseCase tracking;
    private MetaWhatsAppWebhook webhook;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void reset() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(28);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        clock = new MutableClock(FIRST.minusSeconds(3600));
        meta = new FakeMetaServer();
        build(properties(meta.baseUrl(), true, "teste_conexao"));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("com.malyah.accountmanager.notifications")).addAppender(logs);
    }

    @AfterEach
    void close() {
        ((Logger) LoggerFactory.getLogger("com.malyah.accountmanager.notifications")).detachAppender(logs);
        meta.close();
    }

    /** W1 + W2 + W18 + W20 + W21: one accepted request to the administrator; acceptance is not delivery. */
    @Test
    void w1EligibleSummaryIsSentOnceToTheAdministratorAndOnlyAccepted() throws Exception {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        assertThat(tracking.summaryDelivery(A, summaryId).state()).isEqualTo("WAITING");
        clock.set(FIRST.plusSeconds(20));
        job.poll();

        assertThat(meta.requests()).hasSize(1);
        var request = meta.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v23.0/" + PHONE_ID + "/messages");
        assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
        var body = JSON.readTree(request.body());
        assertThat(body.path("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(body.path("to").asString()).isEqualTo("5511987654321");
        assertThat(body.path("type").asString()).isEqualTo("template");
        assertThat(body.path("template").path("name").asString()).isEqualTo("resumo_contas");
        assertThat(body.path("template").path("language").path("code").asString()).isEqualTo("pt_BR");
        assertThat(parameters(body)).containsExactly("05/10/2026, 09:00", "1 conta, total R$ 120,00",
                "05/10 Luz R$ 120,00", "https://contas.malyah.tech/lembretes/resumos/" + summaryId);

        var row = jdbc.queryForMap("select status, provider_message_id, accepted_at, delivered_at, item_count, "
                + "recipient_user_id from whatsapp_deliveries where summary_id = ?", summaryId);
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("provider_message_id")).isEqualTo("wamid.FAKE-1");
        assertThat(((Timestamp) row.get("accepted_at")).toInstant()).isEqualTo(FIRST.plusSeconds(20));
        assertThat(row.get("delivered_at")).isNull();
        assertThat(row.get("item_count")).isEqualTo(1);
        assertThat(row.get("recipient_user_id")).isEqualTo(ADMIN);
        assertThat(jdbc.queryForList("select outcome from whatsapp_attempts", String.class)).containsExactly("ACCEPTED");

        var view = tracking.summaryDelivery(A, summaryId);
        assertThat(view.state()).isEqualTo("ACCEPTED");
        assertThat(view.stateMessage()).isEqualTo("Aceito pela Meta. A entrega ainda não foi confirmada.");
        assertThat(view.recipientMasked()).isEqualTo("+55 ** *****-4321");
        assertThat(view.deliveredAt()).isNull();
        assertThat(view.attempts()).hasSize(1);
        assertThatThrownBy(() -> tracking.summaryDelivery(G, summaryId))
                .isInstanceOf(WhatsAppAdministratorRequiredException.class);
        assertThatThrownBy(() -> tracking.summaryDelivery(O, summaryId))
                .isInstanceOf(ReminderSummaryNotFoundException.class);
        // W2: the guest cannot become a recipient; W20: both members keep the in-app summary, no failure.
        assertThatThrownBy(() -> settings.changeRecipient(G, ReminderSettingsCommand.recipient(3L, "(21) 99876-5432",
                UUID.randomUUID()))).isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThat(recipients("REMINDER_SUMMARY")).containsExactlyInAnyOrder(ADMIN, GUEST);
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).isEmpty();

        clock.set(FIRST.plusSeconds(80));
        job.poll();
        assertThat(meta.requests()).hasSize(1);
        // W21: no full number, token or content stored with the delivery, nor in the logs.
        var stored = String.join("\n", jdbc.queryForList("select row_to_json(d)::text from whatsapp_deliveries d",
                String.class));
        assertThat(stored).doesNotContain("98765", "Luz", TOKEN);
        assertThat(logText()).contains("whatsapp_delivery").doesNotContain("98765", "Luz", TOKEN, "120,00");
    }

    /** W3: disabling or revoking between planning and sending stops the send; no failure notice. */
    @Test
    void w3DisabledOrRevokedBeforeTheCallIsNotSent() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(10));
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(3L, false, UUID.randomUUID())));
        job.poll();
        assertThat(meta.requests()).isEmpty();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/DISABLED");

        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, UUID.randomUUID())));
        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        tx.execute(s -> settings.revokeConsent(A, ReminderSettingsCommand.revocation(5L, UUID.randomUUID())));
        job.poll();
        assertThat(meta.requests()).isEmpty();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("SKIPPED/CONSENT_REVOKED");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).isEmpty();
        assertThat(tracking.summaryDelivery(A, summaryId(SECOND)).reasonMessage())
                .isEqualTo("O consentimento não estava ativo no momento do envio.");
    }

    /** W4: a new administrator or a changed number between planning and sending stops the send. */
    @Test
    void w4AdministratorOrNumberChangedBeforeTheCallIsNotSent() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        // Defence in depth: the number differs from the consented one without the use case (which revokes).
        jdbc.update("update reminder_settings set whatsapp_recipient = '+5511912345678' where space_id = ?", SPACE);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/RECIPIENT_CHANGED");

        jdbc.update("update reminder_settings set whatsapp_recipient = '+5511987654321' where space_id = ?", SPACE);
        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        memberships.transferAdministration(A, GUEST);
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("SKIPPED/ADMINISTRATOR_CHANGED");
        assertThat(meta.requests()).isEmpty();
    }

    /** W5: paid and cancelled bills leave the message; an empty summary is not sent. */
    @Test
    void w5PaidOrCancelledBeforeTheCallLeaveTheMessage() throws Exception {
        enableWhatsApp();
        var luz = pending("Luz", "120.00", d(10, 5));
        var agua = pending("Água", "80.00", d(10, 6));
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(10));
        settle(luz);
        cancel(agua);
        job.poll();
        assertThat(meta.requests()).isEmpty();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/EMPTY");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).isEmpty();

        var gas = pending("Gás", "50.00", d(10, 6));
        pending("Internet", "99.90", d(10, 6));
        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        settle(gas);
        pending("Nova depois do horário", "10.00", d(10, 6));
        job.poll();
        assertThat(meta.requests()).hasSize(1);
        assertThat(parameters(JSON.readTree(meta.requests().getFirst().body()))).containsExactly(
                "05/10/2026, 18:00", "1 conta, total R$ 99,90", "06/10 Internet R$ 99,90",
                "https://contas.malyah.tech/lembretes/resumos/" + summaryId(SECOND));
        assertThat(jdbc.queryForObject("select item_count from whatsapp_deliveries where summary_id = ?",
                Integer.class, summaryId(SECOND))).isOne();
    }

    /** W6: past the window nothing is sent late; the administrator is told in the application. */
    @Test
    void w6NothingIsSentAfterTheWindow() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(3601));
        job.poll();
        assertThat(meta.requests()).isEmpty();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/WINDOW_CLOSED");
        assertThat(failureCodes()).containsExactly("NOT_SENT_IN_WINDOW");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
    }

    /** W7: concurrent workers and re-executions make one request and one delivery. */
    @Test
    void w7ConcurrentWorkersSendOnce() throws Exception {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        clock.set(FIRST.plusSeconds(10));
        meta.enqueueDelayed(200, """
                {"messages":[{"id":"wamid.SLOW"}]}
                """, 300);
        race(() -> {
            job.deliver(summaryId);
            return null;
        }, () -> {
            job.deliver(summaryId);
            return null;
        }, () -> {
            job.poll();
            return null;
        });
        job.poll();
        assertThat(meta.requests()).hasSize(1);
        assertThat(count("whatsapp_deliveries")).isOne();
        assertThat(count("whatsapp_attempts")).isOne();
        assertThat(delivery(summaryId)).isEqualTo("ACCEPTED/-");
    }

    /** W8: a refusal is recorded with the code only; recipient codes are told apart. */
    @Test
    void w8RejectionsAreRecordedWithoutProviderText() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(400, """
                {"error":{"message":"(#132001) Template name does not exist in the translation","type":"OAuthException",
                 "code":132001,"fbtrace_id":"TRACE-SECRET"}}
                """);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("REJECTED/PROVIDER_REJECTED");
        assertThat(jdbc.queryForObject("select provider_error_code from whatsapp_deliveries", String.class))
                .isEqualTo("132001");
        // H08.5: a permanent refusal suspends the channel; the administrator enables it again after the fix.
        assertThat(settings.view(A).whatsapp().state()).isEqualTo("SUSPENDED");
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, UUID.randomUUID())));

        summaryAt(SECOND);
        meta.enqueue(400, """
                {"error":{"message":"Message undeliverable","code":131026,"fbtrace_id":"T"}}
                """);
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("REJECTED/RECIPIENT_INVALID");
        assertThat(failureCodes()).containsExactlyInAnyOrder("PROVIDER_REJECTED", "RECIPIENT_INVALID");
        var stored = String.join("\n", jdbc.queryForList("select row_to_json(d)::text from whatsapp_deliveries d", String.class));
        assertThat(stored).doesNotContain("Template name", "TRACE-SECRET", "undeliverable");
        assertThat(meta.requests()).hasSize(2);
    }

    /**
     * W9, as changed by H08.5: a provider that is down (connection refused) or throttling did not receive the request,
     * so the same delivery waits for its next attempt; nothing is marked as failed and no notice is sent yet.
     */
    @Test
    void w9UnavailableProviderWaitsForTheNextAttempt() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        build(properties("http://127.0.0.1:" + closedPort, true, "teste_conexao"));
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("RETRY_WAITING/PROVIDER_UNAVAILABLE");
        assertThat(attemptOutcomes(summaryId(FIRST))).containsExactly("FAILED");

        build(properties(meta.baseUrl(), true, "teste_conexao"));
        meta.enqueue(429, """
                {"error":{"message":"rate","code":130429}}
                """);
        clock.set(FIRST.plusSeconds(70));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("RETRY_WAITING/PROVIDER_UNAVAILABLE");
        assertThat(meta.requests()).hasSize(1);
        assertThat(failureCodes()).isEmpty();
        assertThat(settings.view(A).whatsapp().state()).isEqualTo("READY");
    }

    /** W10: timeout, 5xx and a 200 without id are uncertain and never resent; an interrupted attempt too. */
    @Test
    void w10UncertainResultsAreNeverResent() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueueDelayed(200, """
                {"messages":[{"id":"wamid.TOO-LATE"}]}
                """, 1500);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        assertThat(tracking.summaryDelivery(A, summaryId(FIRST)).stateMessage())
                .isEqualTo("Resultado incerto: não há confirmação de que a Meta recebeu. Nada foi reenviado.");

        summaryAt(SECOND);
        meta.enqueue(500, "{}");
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        clock.set(SECOND.plusSeconds(120));
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(failureCodes()).containsExactly("RESULT_UNCERTAIN", "RESULT_UNCERTAIN");
    }

    @Test
    void w10AcceptedWithoutIdAndInterruptedAttemptsAreUncertain() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(200, "{\"messages\":[]}");
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");

        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        // The process stops after the claim and before the answer is recorded.
        var claimed = tx.execute(s -> deliveries.prepare(summaryId(SECOND), clock.instant()));
        assertThat(claimed).isInstanceOf(WhatsAppDeliveryService.Ready.class);
        clock.set(SECOND.plusSeconds(10 + 599));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("ATTEMPTING/-");
        clock.set(SECOND.plusSeconds(10 + 601));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        assertThat(jdbc.queryForObject("select count(*) from whatsapp_attempts where outcome = 'UNCERTAIN'",
                Long.class)).isEqualTo(2);
        assertThat(meta.requests()).hasSize(1);
    }

    /** W11 + W13: signed statuses move forward only, once each, and late ones only fill their instant. */
    @Test
    void w11w13WebhookConfirmsForwardOnlyAndOnce() {
        var summaryId = acceptedSummary();
        assertThat(post(statuses("wamid.FAKE-1", "sent", 1_791_201_700L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("SENT/-");
        assertThat(post(statuses("wamid.FAKE-1", "read", 1_791_201_900L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("READ/-");
        assertThat(post(statuses("wamid.FAKE-1", "delivered", 1_791_201_800L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("READ/-");
        assertThat(post(statuses("wamid.FAKE-1", "read", 1_791_201_999L)).status()).isEqualTo(200);
        assertThat(post(failed("wamid.FAKE-1", 131026)).status()).isEqualTo(200);
        var row = jdbc.queryForMap("select status, sent_at, delivered_at, read_at, failed_at from whatsapp_deliveries");
        assertThat(row.get("status")).isEqualTo("READ");
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(1_791_201_700L));
        assertThat(((Timestamp) row.get("delivered_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(1_791_201_800L));
        assertThat(((Timestamp) row.get("read_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(1_791_201_900L));
        assertThat(row.get("failed_at")).isNull();
        assertThat(count("whatsapp_status_events")).isEqualTo(4);
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).isEmpty();
        var view = tracking.summaryDelivery(A, summaryId);
        assertThat(view.state()).isEqualTo("READ");
        assertThat(view.stateMessage()).isEqualTo("Entregue e lido no WhatsApp do administrador.");
    }

    /** W12: nothing is applied without a valid signature over the exact body. */
    @Test
    void w12InvalidSignaturesAreRejected() {
        acceptedSummary();
        var body = statuses("wamid.FAKE-1", "delivered", 1_791_201_800L);
        assertThat(webhook.receive(body, null).status()).isEqualTo(401);
        assertThat(webhook.receive(body, WebhookSignature.header("outro-segredo", body)).status()).isEqualTo(401);
        assertThat(webhook.receive(body, "sha256=zz").status()).isEqualTo(401);
        var tampered = statuses("wamid.FAKE-1", "read", 1_791_201_800L);
        assertThat(webhook.receive(tampered, WebhookSignature.header(APP_SECRET, body)).status()).isEqualTo(401);
        assertThat(count("whatsapp_status_events")).isZero();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("ACCEPTED/-");
    }

    /** W14: unknown messages, other numbers and inbound user messages are ignored and not stored. */
    @Test
    void w14OnlyKnownMessagesAreCorrelated() {
        acceptedSummary();
        assertThat(post(statuses("wamid.UNKNOWN", "delivered", 1_791_201_800L)).status()).isEqualTo(200);
        var otherNumber = new String(statuses("wamid.FAKE-1", "delivered", 1_791_201_800L), StandardCharsets.UTF_8)
                .replace(PHONE_ID, "999999999999999").getBytes(StandardCharsets.UTF_8);
        assertThat(post(otherNumber).status()).isEqualTo(200);
        var inbound = """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                 "messages":[{"from":"5511987654321","id":"wamid.IN","timestamp":"1791201800","type":"text",
                 "text":{"body":"Paguei a conta de luz"}}]}}]}]}
                """.formatted(PHONE_ID).getBytes(StandardCharsets.UTF_8);
        assertThat(post(inbound).status()).isEqualTo(200);
        assertThat(post("não é json".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(200);
        assertThat(count("whatsapp_status_events")).isZero();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("ACCEPTED/-");

        // A status for an id not recorded yet while an attempt waits for its answer: ask Meta to deliver it again.
        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        tx.execute(s -> deliveries.prepare(summaryId(SECOND), clock.instant()));
        assertThat(post(statuses("wamid.NOT-YET", "sent", 1_791_201_800L)).status()).isEqualTo(503);
    }

    /** W15: a reported failure tells the administrator; bills are never touched by the webhook. */
    @Test
    void w15ReportedFailureNotifiesTheAdministratorAndNeverTouchesBills() {
        enableWhatsApp();
        var luz = pending("Luz", "120.00", d(10, 5));
        var before = expenses.get(A, luz);
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        // A failure that is not about the recipient (131049, "healthy ecosystem"): no suspension (H08.5, see R14).
        assertThat(post(failed("wamid.FAKE-1", 131049)).status()).isEqualTo(200);
        assertThat(delivery(summaryId(FIRST))).isEqualTo("FAILED/DELIVERY_FAILED");
        assertThat(jdbc.queryForObject("select provider_error_code from whatsapp_deliveries", String.class))
                .isEqualTo("131049");
        assertThat(settings.view(A).whatsapp().state()).isEqualTo("READY");
        assertThat(failureCodes()).containsExactly("DELIVERY_FAILED");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).containsExactly(ADMIN);
        var admin = inbox.list(A, null, null, null).items().stream().filter(n -> n.failure() != null).findFirst()
                .orElseThrow();
        assertThat(admin.message()).isEqualTo(WhatsAppFailureReason.DELIVERY_FAILED.message());
        assertThat(inbox.list(G, null, null, null).items()).allSatisfy(n -> assertThat(n.failure()).isNull());
        var after = expenses.get(A, luz);
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(post(statuses("wamid.FAKE-1", "delivered", 1_791_201_999L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId(FIRST))).isEqualTo("FAILED/DELIVERY_FAILED");
    }

    /** W16: the verification handshake, and a webhook that is off answers nothing. */
    @Test
    void w16VerificationFollowsTheProtocol() {
        assertThat(webhook.verify("subscribe", VERIFY, "1158201444")).isEqualTo(
                new MetaWhatsAppWebhook.Reply(200, "1158201444"));
        assertThat(webhook.verify("subscribe", "errado", "1158201444").status()).isEqualTo(403);
        assertThat(webhook.verify("unsubscribe", VERIFY, "1158201444").status()).isEqualTo(403);
        assertThat(webhook.verify("subscribe", VERIFY, null).status()).isEqualTo(403);
        build(properties(meta.baseUrl(), false, "teste_conexao"));
        assertThat(webhook.verify("subscribe", VERIFY, "1158201444").status()).isEqualTo(404);
        var body = statuses("wamid.FAKE-1", "sent", 1L);
        assertThat(webhook.receive(body, WebhookSignature.header(APP_SECRET, body)).status()).isEqualTo(404);
    }

    /** W19: the administrator test is idempotent, spaced and only to the consented number; no financial data. */
    @Test
    void w19TestMessageToTheConsentedAdministrator() throws Exception {
        assertThatThrownBy(() -> tracking.sendTest(A, UUID.randomUUID()))
                .isInstanceOf(WhatsAppTestUnavailableException.class).hasMessageContaining("consentimento");
        consentWithoutEnabling();
        assertThatThrownBy(() -> tracking.sendTest(G, UUID.randomUUID()))
                .isInstanceOf(WhatsAppAdministratorRequiredException.class);
        clock.set(FIRST);
        var key = UUID.randomUUID();
        var first = tracking.sendTest(A, key);
        assertThat(first.kind()).isEqualTo("TEST");
        assertThat(first.state()).isEqualTo("ACCEPTED");
        assertThat(first.recipientMasked()).isEqualTo("+55 ** *****-4321");
        assertThat(meta.requests()).hasSize(1);
        var body = JSON.readTree(meta.requests().getFirst().body());
        assertThat(body.path("template").path("name").asString()).isEqualTo("teste_conexao");
        assertThat(body.path("template").path("components").isMissingNode()).isTrue();
        assertThat(body.path("to").asString()).isEqualTo("5511987654321");
        assertThat(tracking.sendTest(A, key)).isEqualTo(first);
        assertThat(meta.requests()).hasSize(1);
        clock.set(FIRST.plusSeconds(30));
        assertThatThrownBy(() -> tracking.sendTest(A, UUID.randomUUID()))
                .isInstanceOf(WhatsAppTestUnavailableException.class)
                .extracting(e -> ((WhatsAppTestUnavailableException) e).code()).isEqualTo("WHATSAPP_TEST_TOO_SOON");
        assertThat(recipients("WHATSAPP_DELIVERY_FAILURE")).isEmpty();

        build(properties(meta.baseUrl(), true, ""));
        clock.set(FIRST.plusSeconds(120));
        assertThatThrownBy(() -> tracking.sendTest(A, UUID.randomUUID()))
                .isInstanceOf(WhatsAppTestUnavailableException.class)
                .extracting(e -> ((WhatsAppTestUnavailableException) e).code())
                .isEqualTo("WHATSAPP_TEST_TEMPLATE_MISSING");
        build(properties(meta.baseUrl(), false, "teste_conexao"));
        assertThatThrownBy(() -> tracking.sendTest(A, UUID.randomUUID()))
                .isInstanceOf(WhatsAppTestUnavailableException.class)
                .extracting(e -> ((WhatsAppTestUnavailableException) e).code()).isEqualTo("PROVIDER_DISABLED");
    }

    /** W18: a summary generated while the channel was not ready says why it was not planned. */
    @Test
    void w18SummaryWithoutPlannedChannelExplainsWhy() {
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var view = tracking.summaryDelivery(A, summaryId());
        assertThat(view.state()).isEqualTo("NOT_PLANNED");
        assertThat(view.reason()).isEqualTo("RECIPIENT_REQUIRED");
        assertThat(view.reasonMessage()).isEqualTo("Sem número cadastrado quando o resumo foi gerado.");
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(meta.requests()).isEmpty();
        assertThat(count("whatsapp_deliveries")).isZero();
    }

    // ---------------------------------------------------------------------------------------------------------
    // H08.5 (matrix R1–R19 in docs/evidencias/H08.5.md): retries, expiry, suspension and reconciliation, with the
    // SIMULATED Meta server answering failures on purpose. Nothing here makes Meta itself unavailable.
    // ---------------------------------------------------------------------------------------------------------

    static final String THROTTLED = """
            {"error":{"message":"rate","code":130429}}
            """;

    /** R6 + R15 + R17: a certain transient failure is retried in the same delivery until accepted. */
    @Test
    void r6TransientFailuresAreRetriedInTheSameDeliveryUntilAccepted() throws Exception {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId)).isEqualTo("RETRY_WAITING/PROVIDER_UNAVAILABLE");
        assertThat(nextAttempt(summaryId)).isEqualTo(FIRST.plusSeconds(70));
        var waiting = tracking.summaryDelivery(A, summaryId);
        assertThat(waiting.state()).isEqualTo("RETRY_WAITING");
        assertThat(waiting.nextAttemptAt()).isEqualTo(FIRST.plusSeconds(70));
        // R15: an unavailable provider never touches the number, the consent or the channel.
        var channel = settings.view(A).whatsapp();
        assertThat(channel.state()).isEqualTo("READY");
        assertThat(channel.suspension()).isNull();
        assertThat(channel.consent().active()).isTrue();
        assertThat(channel.recipient()).isEqualTo("+5511987654321");
        assertThat(failureCodes()).isEmpty();

        clock.set(FIRST.plusSeconds(69));
        job.poll();
        assertThat(meta.requests()).hasSize(1);
        meta.enqueue(503, """
                {"error":{"message":"Service temporarily unavailable","code":131016}}
                """);
        clock.set(FIRST.plusSeconds(70));
        job.poll();
        assertThat(delivery(summaryId)).isEqualTo("RETRY_WAITING/PROVIDER_UNAVAILABLE");
        assertThat(nextAttempt(summaryId)).isEqualTo(FIRST.plusSeconds(370));
        clock.set(FIRST.plusSeconds(370));
        job.poll();
        assertThat(meta.requests()).hasSize(3);
        assertThat(delivery(summaryId)).isEqualTo("ACCEPTED/-");
        assertThat(attemptOutcomes(summaryId)).containsExactly("FAILED", "FAILED", "ACCEPTED");
        assertThat(count("whatsapp_deliveries")).isOne();
        assertThat(count("reminder_summaries")).isOne();
        // Each attempt carries its own reference; the same template and content every time.
        var references = meta.requests().stream().map(r -> reference(r.body())).toList();
        assertThat(references).doesNotHaveDuplicates().containsExactlyElementsOf(jdbc.queryForList(
                "select a.id::text from whatsapp_attempts a order by a.attempt_number", String.class));
        for (var request : meta.requests())
            assertThat(parameters(JSON.readTree(request.body())).get(1)).isEqualTo("1 conta, total R$ 120,00");
        assertThat(failureCodes()).isEmpty();
        assertThat(recipients("REMINDER_SUMMARY")).containsExactlyInAnyOrder(ADMIN, GUEST);
        clock.set(FIRST.plusSeconds(900));
        job.poll();
        assertThat(meta.requests()).hasSize(3);
    }

    /** R7: the window never grows; after the last attempt that fits, the summary expires and is never sent. */
    @Test
    void r7RetriesExpireWithinOneHourOfTheOriginalSlot() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        for (int i = 0; i < 6; i++) meta.enqueue(429, THROTTLED);
        for (var at : List.of(10, 70, 370, 1270, 3070)) {
            clock.set(FIRST.plusSeconds(at));
            job.poll();
        }
        assertThat(meta.requests()).hasSize(5);
        assertThat(delivery(summaryId)).isEqualTo("FAILED/NOT_SENT_IN_WINDOW");
        assertThat(attemptOutcomes(summaryId)).containsExactly("FAILED", "FAILED", "FAILED", "FAILED", "FAILED");
        assertThat(jdbc.queryForObject("select max(started_at) from whatsapp_attempts", Timestamp.class)
                .toInstant()).isBefore(FIRST.plusSeconds(3600));
        assertThat(failureCodes()).containsExactly("NOT_SENT_IN_WINDOW");
        clock.set(FIRST.plusSeconds(3700));
        job.poll();
        assertThat(meta.requests()).hasSize(5);
        assertThat(tracking.summaryDelivery(A, summaryId).reasonMessage())
                .isEqualTo(WhatsAppFailureReason.NOT_SENT_IN_WINDOW.message());
    }

    /** R8: the provider's wait is honored, and the next slot ends the retry before its instant. */
    @Test
    void r8TheNextSlotClosesAPendingRetryAndOnlyTheCurrentSummaryIsSent() throws Exception {
        enableWhatsApp();
        tx.execute(s -> settings.changeSchedule(A, ReminderSettingsCommand.schedule(3L, "09:00", "09:30",
                UUID.randomUUID())));
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var firstId = summaryId();
        meta.enqueueRetryAfter(429, THROTTLED, "1500");
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(nextAttempt(firstId)).isEqualTo(FIRST.plusSeconds(1510));
        clock.set(FIRST.plusSeconds(1500));
        job.poll();
        assertThat(meta.requests()).hasSize(1);
        var nextSlot = FIRST.plusSeconds(1800);
        summaryAt(nextSlot);
        job.poll();
        assertThat(delivery(firstId)).isEqualTo("FAILED/NOT_SENT_IN_WINDOW");
        assertThat(delivery(summaryId(nextSlot))).isEqualTo("ACCEPTED/-");
        assertThat(meta.requests()).hasSize(2);
        assertThat(parameters(JSON.readTree(meta.requests().get(1).body())).getFirst())
                .isEqualTo("05/10/2026, 09:30");
        assertThat(attemptOutcomes(firstId)).containsExactly("FAILED");
    }

    /** R9: after a restart a retry still inside its window is resumed; one outside it is closed unsent. */
    @Test
    void r9RestartResumesOnlyRetriesStillInsideTheirWindow() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        build(properties(meta.baseUrl(), true, "teste_conexao"));
        clock.set(FIRST.plusSeconds(120));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("ACCEPTED/-");
        assertThat(meta.requests()).hasSize(2);

        summaryAt(SECOND);
        meta.enqueue(429, THROTTLED);
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("RETRY_WAITING/PROVIDER_UNAVAILABLE");
        build(properties(meta.baseUrl(), true, "teste_conexao"));
        clock.set(SECOND.plusSeconds(3601));
        job.poll();
        assertThat(delivery(summaryId(SECOND))).isEqualTo("FAILED/NOT_SENT_IN_WINDOW");
        assertThat(meta.requests()).hasSize(3);
        assertThat(failureCodes()).containsExactly("NOT_SENT_IN_WINDOW");
        assertThat(logText()).contains("whatsapp_retry_closed");
    }

    /** R10: after a long unavailability nothing old is sent: only the current slot's summary goes out. */
    @Test
    void r10NothingAccumulatesAfterAnUnavailability() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        // The server is down (no job runs) from 12:00:10 until after the second slot.
        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(delivery(summaryId(FIRST))).isEqualTo("FAILED/NOT_SENT_IN_WINDOW");
        assertThat(delivery(summaryId(SECOND))).isEqualTo("ACCEPTED/-");
        assertThat(count("reminder_summaries")).isEqualTo(2);
        assertThat(count("whatsapp_attempts")).isEqualTo(2);
        // A whole day missed while down: the slots of the day are MISSED, nothing retroactive is generated.
        var nextDay = Instant.parse("2026-10-07T12:00:00Z");
        summaryAt(nextDay.plusSeconds(10));
        clock.set(nextDay.plusSeconds(20));
        job.poll();
        assertThat(jdbc.queryForObject("select count(*) from reminder_slot_runs where local_date = '2026-10-06'",
                Long.class)).isZero();
        assertThat(meta.requests()).hasSize(3);
    }

    /** R1 + R2 + R19: each retry recomposes the content; paid, cancelled and moved bills leave, values update. */
    @Test
    void r1r2EachRetryRevalidatesTheBillsAndAChangedDueDateSendsNothingExtra() throws Exception {
        enableWhatsApp();
        var luz = pending("Luz", "120.00", d(10, 5));
        var agua = pending("Água", "80.00", d(10, 6));
        var gas = pending("Gás", "50.00", d(10, 7));
        var net = pending("Internet", "99.90", d(10, 8));
        summaryAt(FIRST);
        var summaryId = summaryId();
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(parameters(JSON.readTree(meta.requests().getFirst().body())).get(1))
                .isEqualTo("4 contas, total R$ 349,90");
        clock.set(FIRST.plusSeconds(30));
        settle(luz);
        cancel(agua);
        correct(gas, "55.00", d(10, 7));
        correct(net, "99.90", d(10, 20));
        clock.set(FIRST.plusSeconds(70));
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(parameters(JSON.readTree(meta.requests().get(1).body()))).containsExactly("05/10/2026, 09:00",
                "1 conta, total R$ 55,00", "07/10 Gás R$ 55,00",
                "https://contas.malyah.tech/lembretes/resumos/" + summaryId);
        assertThat(jdbc.queryForObject("select item_count from whatsapp_deliveries", Integer.class)).isOne();
        // RF-ALT-14: moving a due date into today after the send does not trigger another message.
        correct(net, "99.90", d(10, 5));
        clock.set(FIRST.plusSeconds(600));
        job.poll();
        summaryAt(FIRST.plusSeconds(900));
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(count("reminder_summaries")).isOne();
    }

    /** R3: a retry whose bills were all paid is closed without any message and without a failure notice. */
    @Test
    void r3ARetryWithoutEligibleBillsIsClosedWithoutSending() {
        enableWhatsApp();
        var luz = pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        settle(luz);
        clock.set(FIRST.plusSeconds(70));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/EMPTY");
        assertThat(meta.requests()).hasSize(1);
        assertThat(failureCodes()).isEmpty();
    }

    /** R4 + R5: disabling, a new administrator or a new number stop a waiting retry, even before its instant. */
    @Test
    void r4r5DisablingOrChangingTheRecipientStopsAWaitingRetry() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(3L, false, UUID.randomUUID())));
        clock.set(FIRST.plusSeconds(20));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("SKIPPED/DISABLED");

        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, UUID.randomUUID())));
        summaryAt(SECOND);
        meta.enqueue(429, THROTTLED);
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        tx.execute(s -> settings.changeRecipient(A, ReminderSettingsCommand.recipient(5L, "(21) 99876-5432",
                UUID.randomUUID())));
        clock.set(SECOND.plusSeconds(70));
        job.poll();
        // Saving another number revokes the consent of the old one, which is what the revalidation finds first.
        assertThat(delivery(summaryId(SECOND))).isEqualTo("SKIPPED/CONSENT_REVOKED");

        var nextDay = Instant.parse("2026-10-06T12:00:00Z");
        tx.execute(s -> settings.grantConsent(A, ReminderSettingsCommand.consent(6L, "(21) 99876-5432", true,
                UUID.randomUUID())));
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(7L, true, UUID.randomUUID())));
        summaryAt(nextDay);
        meta.enqueue(429, THROTTLED);
        clock.set(nextDay.plusSeconds(10));
        job.poll();
        memberships.transferAdministration(A, GUEST);
        clock.set(nextDay.plusSeconds(70));
        job.poll();
        assertThat(delivery(summaryId(nextDay))).isEqualTo("SKIPPED/ADMINISTRATOR_CHANGED");
        assertThat(meta.requests()).hasSize(3);
        assertThat(failureCodes()).isEmpty();
    }

    /** R11: concurrent workers on a due retry make exactly one more request. */
    @Test
    void r11ConcurrentWorkersRetryOnce() throws Exception {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        var deliveryId = deliveryId(summaryId);
        clock.set(FIRST.plusSeconds(70));
        meta.enqueueDelayed(200, """
                {"messages":[{"id":"wamid.SLOW"}]}
                """, 300);
        race(() -> {
            job.retry(deliveryId);
            return null;
        }, () -> {
            job.retry(deliveryId);
            return null;
        }, () -> {
            job.poll();
            return null;
        });
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(attemptOutcomes(summaryId)).containsExactly("FAILED", "ACCEPTED");
        assertThat(delivery(summaryId)).isEqualTo("ACCEPTED/-");
    }

    /** R12: a lease recovered after a possibly accepted retry is never resent; the webhook reconciles it. */
    @Test
    void r12ARecoveredLeaseIsNeverResentAndTheWebhookReconcilesIt() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        meta.enqueue(429, THROTTLED);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        clock.set(FIRST.plusSeconds(70));
        // The worker claims attempt 2 and Meta accepts it, but the process stops before recording the answer.
        var ready = (WhatsAppDeliveryService.Ready) tx.execute(s -> deliveries.prepareRetry(deliveryId(summaryId),
                clock.instant()));
        assertThat(sender.send(ready.message()).providerMessageId()).isEqualTo("wamid.FAKE-1");
        clock.set(FIRST.plusSeconds(70 + 601));
        job.poll();
        assertThat(delivery(summaryId)).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        clock.set(FIRST.plusSeconds(1500));
        job.poll();
        assertThat(meta.requests()).hasSize(2);
        assertThat(failureCodes()).containsExactly("RESULT_UNCERTAIN");

        assertThat(post(statusesWithReference("wamid.FAKE-1", "sent", 1_791_201_700L, ready.attemptId().toString()))
                .status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("SENT/-");
        var view = tracking.summaryDelivery(A, summaryId);
        assertThat(view.state()).isEqualTo("SENT");
        assertThat(view.reconciledAt()).isEqualTo(FIRST.plusSeconds(1500));
        assertThat(attemptOutcomes(summaryId)).containsExactly("FAILED", "ACCEPTED");
        // Later statuses find the message by its id; repeated or older ones change nothing.
        assertThat(post(statuses("wamid.FAKE-1", "delivered", 1_791_201_800L)).status()).isEqualTo(200);
        assertThat(post(statusesWithReference("wamid.FAKE-1", "sent", 1_791_201_700L, ready.attemptId().toString()))
                .status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("DELIVERED/-");
        assertThat(meta.requests()).hasSize(2);
    }

    /** R13: a timeout is ambiguous: no automatic resend inside the window; the echoed reference reconciles it. */
    @Test
    void r13AnAmbiguousTimeoutIsNotResentAndIsReconciledByItsReference() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        var summaryId = summaryId();
        meta.enqueueDelayed(200, """
                {"messages":[{"id":"wamid.LATE"}]}
                """, 1500);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId)).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        for (var at : List.of(80, 400, 1300, 3000)) {
            clock.set(FIRST.plusSeconds(at));
            job.poll();
        }
        assertThat(meta.requests()).hasSize(1);
        // A status of an unknown id without reference is ignored; with the echoed reference it reconciles.
        assertThat(post(statuses("wamid.LATE", "delivered", 1_791_201_800L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("UNCERTAIN/RESULT_UNCERTAIN");
        var reference = reference(meta.requests().getFirst().body());
        assertThat(post(statusesWithReference("wamid.LATE", "delivered", 1_791_201_800L, reference)).status())
                .isEqualTo(200);
        assertThat(delivery(summaryId)).isEqualTo("DELIVERED/-");
        assertThat(jdbc.queryForObject("select provider_message_id from whatsapp_deliveries", String.class))
                .isEqualTo("wamid.LATE");
    }

    /** R14 + R16: a permanent failure suspends the channel with guidance; re-enabling resumes it next slot. */
    @Test
    void r14APermanentFailureSuspendsUntilTheAdministratorEnablesAgain() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(400, """
                {"error":{"message":"Message undeliverable","code":131026}}
                """);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("REJECTED/RECIPIENT_INVALID");
        var channel = settings.view(A);
        assertThat(channel.version()).isEqualTo(4);
        assertThat(channel.whatsapp().state()).isEqualTo("SUSPENDED");
        assertThat(channel.whatsapp().enabled()).isFalse();
        assertThat(channel.whatsapp().consent().active()).isTrue();
        assertThat(channel.whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(channel.whatsapp().suspension().reason()).isEqualTo("RECIPIENT_INVALID");
        assertThat(channel.whatsapp().suspension().message()).contains("reative o canal");
        var event = settings.events(A).items().getFirst();
        assertThat(event.type()).isEqualTo("CHANNEL_SUSPENDED");
        assertThat(event.actorDisplayName()).isEqualTo("Sistema");
        assertThat(settings.view(G).whatsapp().recipient()).isNull();

        summaryAt(SECOND);
        clock.set(SECOND.plusSeconds(10));
        job.poll();
        var skipped = tracking.summaryDelivery(A, summaryId(SECOND));
        assertThat(skipped.state()).isEqualTo("NOT_PLANNED");
        assertThat(skipped.reason()).isEqualTo("SUSPENDED");
        assertThat(meta.requests()).hasSize(1);
        assertThat(failureCodes()).containsExactly("RECIPIENT_INVALID");
        assertThat(recipients("REMINDER_SUMMARY")).containsExactlyInAnyOrder(ADMIN, GUEST, ADMIN, GUEST);
        assertThat(inbox.list(G, null, null, null).items()).allSatisfy(n -> assertThat(n.failure()).isNull());

        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, UUID.randomUUID())));
        assertThat(settings.view(A).whatsapp().state()).isEqualTo("READY");
        var nextDay = Instant.parse("2026-10-06T12:00:00Z");
        summaryAt(nextDay);
        clock.set(nextDay.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(nextDay))).isEqualTo("ACCEPTED/-");

        // R16: Meta reports a recipient failure later by webhook: suspended again, once; out of order changes nothing.
        assertThat(post(failed("wamid.FAKE-1", 131026)).status()).isEqualTo(200);
        assertThat(post(failed("wamid.FAKE-1", 131026)).status()).isEqualTo(200);
        assertThat(post(statuses("wamid.FAKE-1", "delivered", 1_791_201_999L)).status()).isEqualTo(200);
        assertThat(delivery(summaryId(nextDay))).isEqualTo("FAILED/DELIVERY_FAILED");
        assertThat(settings.view(A).whatsapp().suspension().reason()).isEqualTo("RECIPIENT_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from reminder_settings_events where event_type = "
                + "'CHANNEL_SUSPENDED'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select failure_code from member_notifications where summary_id = ? and "
                + "type = 'WHATSAPP_DELIVERY_FAILURE'", String.class, summaryId(nextDay)))
                .isEqualTo("RECIPIENT_UNREACHABLE");
    }

    /** R14b: a permanent refusal of the channel (template, sender, token) suspends it too; nothing is retried. */
    @Test
    void r14bAPermanentRefusalOfTheChannelSuspendsWithoutRetry() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        meta.enqueue(400, """
                {"error":{"message":"(#132001) Template name does not exist","code":132001}}
                """);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        clock.set(FIRST.plusSeconds(600));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("REJECTED/PROVIDER_REJECTED");
        assertThat(meta.requests()).hasSize(1);
        assertThat(settings.view(A).whatsapp().suspension().reason()).isEqualTo("PROVIDER_REJECTED");
    }

    private Instant nextAttempt(UUID summaryId) {
        return jdbc.queryForObject("select next_attempt_at from whatsapp_deliveries where summary_id = ?",
                Timestamp.class, summaryId).toInstant();
    }

    private UUID deliveryId(UUID summaryId) {
        return jdbc.queryForObject("select id from whatsapp_deliveries where summary_id = ?", UUID.class, summaryId);
    }

    private List<String> attemptOutcomes(UUID summaryId) {
        return jdbc.queryForList("select a.outcome from whatsapp_attempts a join whatsapp_deliveries d on "
                + "d.id = a.delivery_id where d.summary_id = ? order by a.attempt_number", String.class, summaryId);
    }

    private static String reference(String body) {
        return JSON.readTree(body).path("biz_opaque_callback_data").asString();
    }

    static byte[] statusesWithReference(String id, String status, long timestamp, String reference) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},"statuses":[{"id":"%s",
                 "status":"%s","timestamp":"%d","recipient_id":"5511987654321","biz_opaque_callback_data":"%s"}]}}]}]}
                """.formatted(PHONE_ID, id, status, timestamp, reference).getBytes(StandardCharsets.UTF_8);
    }

    private void correct(UUID id, String amount, LocalDate due) {
        var current = expenses.get(A, id);
        tx.execute(s -> expenses.correct(A, new CorrectExpenseCommand(id, current.version(), ExpenseStatus.PENDING,
                current.description(), amount, due, null, null, null, null, null, UUID.randomUUID())));
    }

    private UUID acceptedSummary() {
        enableWhatsApp();
        pending("Luz", "120.00", d(10, 5));
        summaryAt(FIRST);
        clock.set(FIRST.plusSeconds(10));
        job.poll();
        assertThat(delivery(summaryId(FIRST))).isEqualTo("ACCEPTED/-");
        return summaryId(FIRST);
    }

    private void build(MetaWhatsAppProperties properties) {
        context = new AuthenticatedUserContextService(contextRepository());
        var members = new JdbcFinancialMemberAccess(jdbc);
        var categories = new JdbcCategoryRepository(jdbc);
        expenses = new ExpenseService(new JdbcExpenseRepository(jdbc), context, UUID::randomUUID, clock, members,
                categories);
        var materializer = new JdbcRecurringExpenseMaterializer(jdbc, tx);
        var generation = new JdbcRecurrenceGenerationJob(jdbc, tx, materializer, clock, Duration.ofMinutes(2), 25);
        var provider = new MetaWhatsAppProvider(properties);
        sender = provider;
        settingsService = new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbc), context, members,
                provider, clock, UUID::randomUUID);
        settings = new TransactionalReminderSettingsUseCase(settingsService, tx);
        var notifications = new JdbcMemberNotificationRepository(jdbc);
        var summaries = new ReminderSummaryService(new JdbcReminderSummaryRepository(jdbc), notifications,
                new JdbcReminderSettingsRepository(jdbc), new JdbcExpenseReminderQueries(jdbc),
                new RecurrenceForecastCatalog(RecurrenceTestFixtures.service(jdbc, context, categories, members,
                        clock, materializer, null)), generation, provider, context, clock, UUID::randomUUID,
                "https://contas.malyah.tech");
        summaryJob = new ReminderSummaryJob(summaries, tx, clock);
        deliveries = new WhatsAppDeliveryService(new JdbcWhatsAppDeliveryRepository(jdbc),
                new JdbcReminderSummaryRepository(jdbc), new JdbcReminderSettingsRepository(jdbc), notifications,
                summaries, provider, context, clock, UUID::randomUUID);
        job = new WhatsAppDeliveryJob(deliveries, provider, tx);
        tracking = new TransactionalWhatsAppDeliveryUseCase(deliveries, provider, tx);
        webhook = new MetaWhatsAppWebhook(properties, deliveries, tx);
        inbox = new TransactionalMemberNotificationUseCase(new MemberNotificationService(notifications, context,
                clock, "https://contas.malyah.tech"), tx);
        memberships = IdentityTestFixtures.memberships(jdbc, tx, clock, List.of(),
                List.of(settingsService::afterAdministrationTransferred));
    }

    static MetaWhatsAppProperties properties(String baseUrl, boolean enabled, String testTemplate) {
        return new MetaWhatsAppProperties(enabled, baseUrl, "v23.0", PHONE_ID, TOKEN, APP_SECRET, VERIFY,
                "resumo_contas", "pt_BR", testTemplate, "pt_BR", Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    private MetaWhatsAppWebhook.Reply post(byte[] body) {
        return webhook.receive(body, WebhookSignature.header(APP_SECRET, body));
    }

    static byte[] statuses(String id, String status, long timestamp) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"display_phone_number":"551100000000",
                 "phone_number_id":"%s"},"statuses":[{"id":"%s","status":"%s","timestamp":"%d",
                 "recipient_id":"5511987654321"}]}}]}]}
                """.formatted(PHONE_ID, id, status, timestamp).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] failed(String id, int code) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},"statuses":[{"id":"%s",
                 "status":"failed","timestamp":"1791202000","recipient_id":"5511987654321",
                 "errors":[{"code":%d,"title":"Message undeliverable"}]}]}}]}]}
                """.formatted(PHONE_ID, id, code).getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> parameters(JsonNode body) {
        var list = new java.util.ArrayList<String>();
        for (var parameter : body.path("template").path("components").path(0).path("parameters"))
            list.add(parameter.path("text").asString());
        return list;
    }

    private void summaryAt(Instant instant) {
        clock.set(instant);
        summaryJob.poll();
    }

    private UUID summaryId() {
        return jdbc.queryForObject("select id from reminder_summaries", UUID.class);
    }

    private UUID summaryId(Instant scheduledAt) {
        return jdbc.queryForObject("select id from reminder_summaries where scheduled_at = ?", UUID.class,
                Timestamp.from(scheduledAt));
    }

    /** {@code STATUS/REASON}, the reason being the skip reason or the failure code ("-" when none). */
    private String delivery(UUID summaryId) {
        return jdbc.queryForObject("select status || '/' || coalesce(skip_reason, failure_code, '-') "
                + "from whatsapp_deliveries where summary_id = ?", String.class, summaryId);
    }

    private List<String> failureCodes() {
        return jdbc.queryForList("select failure_code from member_notifications where type = "
                + "'WHATSAPP_DELIVERY_FAILURE' order by created_at, failure_code", String.class);
    }

    private List<UUID> recipients(String type) {
        return jdbc.queryForList("select recipient_user_id from member_notifications where type = ?", UUID.class,
                type);
    }

    private String logText() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private void enableWhatsApp() {
        consentWithoutEnabling();
        tx.execute(s -> settings.changeChannel(A, ReminderSettingsCommand.channel(2L, true, UUID.randomUUID())));
    }

    private void consentWithoutEnabling() {
        tx.execute(s -> settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, "(11) 98765-4321",
                UUID.randomUUID())));
        tx.execute(s -> settings.grantConsent(A, ReminderSettingsCommand.consent(1L, "(11) 98765-4321", true,
                UUID.randomUUID())));
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
        tx.execute(s -> expenses.cancel(A, new CancelExpenseCommand(id, expenses.get(A, id).version(),
                "Não será cobrada", UUID.randomUUID())));
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
                + "created_at) values (?,?,?,'{test}x',true,?)", id, name, email,
                Timestamp.from(FIRST.minusSeconds(864000)));
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
