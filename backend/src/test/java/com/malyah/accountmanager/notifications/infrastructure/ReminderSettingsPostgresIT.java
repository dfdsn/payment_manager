package com.malyah.accountmanager.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.infrastructure.IdentityTestFixtures;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.notifications.application.NotificationAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsIdempotencyConflictException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSettingsVersionConflictException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsView;
import com.malyah.accountmanager.notifications.application.WhatsAppActivationRequiredException;
import com.malyah.accountmanager.notifications.application.WhatsAppConsentNotActiveException;
import com.malyah.accountmanager.notifications.application.WhatsAppRecipientMismatchException;
import com.malyah.accountmanager.notifications.domain.NotificationValidationException;

/** H08.1 matrix C1–C16 (docs/evidencias/H08.1.md) on PostgreSQL 17.6 with the real identity adapters. */
class ReminderSettingsPostgresIT {
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    static final UUID SPACE = UUID.fromString("a8000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("a9000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("a8000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("a8000000-0000-0000-0000-000000000003");
    static final UUID OUTSIDER = UUID.fromString("a9000000-0000-0000-0000-000000000002");
    static final UUID LONER = UUID.fromString("a9000000-0000-0000-0000-000000000003");
    static final String A = "admin@example.com";
    static final String G = "guest@example.com";
    static final String O = "other@example.com";
    static final String N1 = "(11) 98765-4321";
    static final String N2 = "+55 21 99876-5432";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_reminder_settings_test").withUsername("account_manager")
            .withPassword("test-only-password");

    static {
        POSTGRES.start();
    }

    DataSource dataSource;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AuthenticatedUserContextQuery contexts;
    ReminderSettingsUseCase settings;
    ReminderSettingsService handler;

    @BeforeEach
    void reset() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(24);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertSpace(SPACE, "Casa");
        insertSpace(OTHER, "Outra");
        insertUser(ADMIN, "Admin", A, SPACE, "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", G, SPACE, "GUEST");
        insertUser(OUTSIDER, "Outro", O, OTHER, "ADMINISTRATOR");
        jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,"
                + "created_at) values (?,?,?,'{test}x',true,?)", LONER, "Sem espaço", "loner@example.com",
                Timestamp.from(NOW));
        contexts = IdentityTestFixtures.contexts(jdbc);
        handler = new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbc), contexts,
                new JdbcFinancialMemberAccess(jdbc), new UnavailableWhatsAppProvider(), Clock.fixed(NOW, ZoneOffset.UTC),
                UUID::randomUUID);
        settings = new TransactionalReminderSettingsUseCase(handler, tx);
    }

    @Test
    void c1InitialSettingsUseTheDefaultsAndEnableNothing() {
        var view = settings.view(A);
        assertThat(view.version()).isZero();
        assertThat(view.canManage()).isTrue();
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.schedule().firstTime()).isEqualTo("09:00");
        assertThat(view.schedule().secondTime()).isEqualTo("18:00");
        assertThat(view.whatsapp().enabled()).isFalse();
        assertThat(view.whatsapp().hasRecipient()).isFalse();
        assertThat(view.whatsapp().consent().active()).isFalse();
        assertThat(view.whatsapp().provider().available()).isFalse();
        assertThat(view.whatsapp().provider().code()).isEqualTo("PROVIDER_NOT_IMPLEMENTED");
        assertThat(view.whatsapp().state()).isEqualTo("RECIPIENT_REQUIRED");
        assertThat(count("reminder_settings")).isZero();
    }

    @Test
    void c2AndC3NumberIsNormalizedPersistedAndHiddenFromTheGuest() {
        var saved = settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        assertThat(saved.version()).isOne();
        assertThat(saved.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(jdbc.queryForObject("select whatsapp_recipient from reminder_settings where space_id = ?",
                String.class, SPACE)).isEqualTo("+5511987654321");
        var reread = settings.view(A);
        assertThat(reread.whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(reread.version()).isOne();
        assertThat(reread.updatedAt()).isEqualTo(NOW);

        var guest = settings.view(G);
        assertThat(guest.canManage()).isFalse();
        assertThat(guest.whatsapp().hasRecipient()).isTrue();
        assertThat(guest.whatsapp().recipient()).isNull();
        assertThat(guest.whatsapp().recipientFormatted()).isNull();
        assertThat(guest.whatsapp().recipientLastDigits()).isEqualTo("4321");
        assertThat(guest.schedule().firstTime()).isEqualTo("09:00");
        assertThat(jdbc.queryForObject("select detail from reminder_settings_events", String.class))
                .isEqualTo("nenhum -> +55 ** *****-4321");
    }

    @Test
    void c4InvalidNumbersChangeNothing() {
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        for (var raw : new String[] {"1234", "+1 555 123 4567", "(20) 98765-4321", "(11) 3876-5432",
                "(11) 88765-4321", "abc", ""}) {
            assertThatThrownBy(() -> settings.changeRecipient(A, ReminderSettingsCommand.recipient(1L, raw,
                    UUID.randomUUID()))).isInstanceOfSatisfying(NotificationValidationException.class,
                            error -> assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_INVALID"));
        }
        assertThat(settings.view(A).version()).isOne();
        assertThat(settings.view(A).whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(count("reminder_settings_requests")).isOne();
        assertThat(count("reminder_settings_events")).isOne();
    }

    @Test
    void c5ToC7ConsentIsExplicitAndActivationIsSeparateFromTheProvider() {
        assertThatThrownBy(() -> settings.changeChannel(A, ReminderSettingsCommand.channel(0L, true, UUID.randomUUID())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_REQUIRED"));
        assertThatThrownBy(() -> settings.grantConsent(A, ReminderSettingsCommand.consent(0L, N1, true, UUID.randomUUID())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_REQUIRED"));
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        assertThatThrownBy(() -> settings.changeChannel(A, ReminderSettingsCommand.channel(1L, true, UUID.randomUUID())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_CONSENT_REQUIRED"));
        assertThatThrownBy(() -> settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, false, UUID.randomUUID())))
                .isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N2, true, UUID.randomUUID())))
                .isInstanceOf(WhatsAppRecipientMismatchException.class);
        assertThat(count("whatsapp_consents")).isZero();
        assertThat(settings.view(A).whatsapp().enabled()).isFalse();

        var consented = settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, UUID.randomUUID()));
        assertThat(consented.version()).isEqualTo(2);
        assertThat(consented.whatsapp().state()).isEqualTo("DISABLED");
        assertThat(consented.whatsapp().consent().grantedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForMap("select user_id, recipient, consent_text_version, granted_at, revoked_at "
                + "from whatsapp_consents")).containsEntry("user_id", ADMIN).containsEntry("recipient", "+5511987654321")
                .containsEntry("consent_text_version", "WHATSAPP-RESUMOS-V1").containsEntry("granted_at", Timestamp.from(NOW))
                .containsEntry("revoked_at", null);

        var enabled = settings.changeChannel(A, ReminderSettingsCommand.channel(2L, true, UUID.randomUUID()));
        assertThat(enabled.version()).isEqualTo(3);
        assertThat(enabled.whatsapp().enabled()).isTrue();
        assertThat(enabled.whatsapp().state()).isEqualTo("PROVIDER_UNAVAILABLE");
        var disabled = settings.changeChannel(A, ReminderSettingsCommand.channel(3L, false, UUID.randomUUID()));
        assertThat(disabled.version()).isEqualTo(4);
        assertThat(disabled.whatsapp().state()).isEqualTo("DISABLED");
        assertThat(disabled.whatsapp().consent().active()).isTrue();
        assertThat(disabled.whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(events()).containsExactly("RECIPIENT_CHANGED", "CONSENT_GRANTED", "CHANNEL_ENABLED",
                "CHANNEL_DISABLED");
    }

    @Test
    void c8RevocationDisablesAndANewConsentIsANewRecord() {
        enabledWithConsent();
        var revoked = settings.revokeConsent(A, ReminderSettingsCommand.revocation(3L, UUID.randomUUID()));
        assertThat(revoked.whatsapp().enabled()).isFalse();
        assertThat(revoked.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(jdbc.queryForMap("select revoked_at, revoked_by_user_id, revocation_reason from whatsapp_consents"))
                .containsEntry("revoked_at", Timestamp.from(NOW)).containsEntry("revoked_by_user_id", ADMIN)
                .containsEntry("revocation_reason", "REVOKED_BY_ADMINISTRATOR");
        assertThatThrownBy(() -> settings.revokeConsent(A, ReminderSettingsCommand.revocation(4L, UUID.randomUUID())))
                .isInstanceOf(WhatsAppConsentNotActiveException.class);
        settings.grantConsent(A, ReminderSettingsCommand.consent(4L, N1, true, UUID.randomUUID()));
        assertThat(count("whatsapp_consents")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from whatsapp_consents where revoked_at is null", Integer.class))
                .isOne();
        assertThat(events()).containsExactly("RECIPIENT_CHANGED", "CONSENT_GRANTED", "CHANNEL_ENABLED",
                "CONSENT_REVOKED", "CHANNEL_DISABLED", "CONSENT_GRANTED");
    }

    @Test
    void c9NewNumberRevokesTheConsentAndTheSameNumberIsANoOp() {
        enabledWithConsent();
        var changed = settings.changeRecipient(A, ReminderSettingsCommand.recipient(3L, N2, UUID.randomUUID()));
        assertThat(changed.version()).isEqualTo(4);
        assertThat(changed.whatsapp().recipient()).isEqualTo("+5521998765432");
        assertThat(changed.whatsapp().enabled()).isFalse();
        assertThat(changed.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(jdbc.queryForObject("select revocation_reason from whatsapp_consents", String.class))
                .isEqualTo("RECIPIENT_CHANGED");
        var same = settings.changeRecipient(A, ReminderSettingsCommand.recipient(4L, "21998765432", UUID.randomUUID()));
        assertThat(same.version()).isEqualTo(4);
        assertThat(events()).containsExactly("RECIPIENT_CHANGED", "CONSENT_GRANTED", "CHANNEL_ENABLED",
                "RECIPIENT_CHANGED", "CONSENT_REVOKED", "CHANNEL_DISABLED");
    }

    @Test
    void c10ScheduleIsValidatedAndPersisted() {
        var saved = settings.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", UUID.randomUUID()));
        assertThat(saved.schedule().firstTime()).isEqualTo("08:30");
        assertThat(settings.view(G).schedule().secondTime()).isEqualTo("20:00");
        assertThat(jdbc.queryForObject("select detail from reminder_settings_events", String.class))
                .isEqualTo("09:00/18:00 -> 08:30/20:00");
        for (var pair : new String[][] {{"18:00", "09:00"}, {"09:00", "09:00"}}) {
            assertThatThrownBy(() -> settings.changeSchedule(A, ReminderSettingsCommand.schedule(1L, pair[0], pair[1],
                    UUID.randomUUID()))).isInstanceOfSatisfying(NotificationValidationException.class,
                            error -> assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID"));
        }
        for (var pair : new String[][] {{"25:00", "20:00"}, {"9h", "20:00"}, {"", "20:00"}}) {
            assertThatThrownBy(() -> settings.changeSchedule(A, ReminderSettingsCommand.schedule(1L, pair[0], pair[1],
                    UUID.randomUUID()))).isInstanceOfSatisfying(NotificationValidationException.class,
                            error -> assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID_FORMAT"));
        }
        assertThatThrownBy(() -> jdbc.update("update reminder_settings set second_time = '08:00' where space_id = ?",
                SPACE)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settings.view(A).schedule().firstTime()).isEqualTo("08:30");
        assertThat(settings.view(A).version()).isOne();
    }

    @Test
    void c11GuestAndOutsidersCannotChangeOrRead() {
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        List<Runnable> attempts = List.of(
                () -> settings.changeSchedule(G, ReminderSettingsCommand.schedule(1L, "08:00", "19:00", UUID.randomUUID())),
                () -> settings.changeRecipient(G, ReminderSettingsCommand.recipient(1L, N2, UUID.randomUUID())),
                () -> settings.grantConsent(G, ReminderSettingsCommand.consent(1L, N1, true, UUID.randomUUID())),
                () -> settings.revokeConsent(G, ReminderSettingsCommand.revocation(1L, UUID.randomUUID())),
                () -> settings.changeChannel(G, ReminderSettingsCommand.channel(1L, true, UUID.randomUUID())),
                () -> settings.events(G));
        attempts.forEach(attempt -> assertThatThrownBy(attempt::run)
                .isInstanceOf(NotificationAdministratorRequiredException.class));
        assertThatThrownBy(() -> settings.view("loner@example.com"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> settings.changeSchedule("loner@example.com",
                ReminderSettingsCommand.schedule(0L, "08:00", "19:00", UUID.randomUUID())))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(settings.view(A).version()).isOne();
        assertThat(count("reminder_settings_events")).isOne();
        assertThat(count("whatsapp_consents")).isZero();
    }

    @Test
    void c12SpacesAreIsolated() {
        settings.changeSchedule(O, ReminderSettingsCommand.schedule(0L, "07:00", "21:00", UUID.randomUUID()));
        settings.changeRecipient(O, ReminderSettingsCommand.recipient(1L, N2, UUID.randomUUID()));
        var mine = settings.view(A);
        assertThat(mine.version()).isZero();
        assertThat(mine.schedule().firstTime()).isEqualTo("09:00");
        assertThat(mine.whatsapp().hasRecipient()).isFalse();
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        assertThat(settings.view(O).whatsapp().recipient()).isEqualTo("+5521998765432");
        assertThat(settings.view(O).version()).isEqualTo(2);
        assertThat(settings.view(A).whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(settings.events(O).items()).hasSize(2);
        assertThat(settings.events(A).items()).hasSize(1);
    }

    @Test
    void c13TransferRevokesTheConsentAndRequiresTheNewAdministratorsOwn() {
        settings.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", UUID.randomUUID()));
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(1L, N1, UUID.randomUUID()));
        settings.grantConsent(A, ReminderSettingsCommand.consent(2L, N1, true, UUID.randomUUID()));
        settings.changeChannel(A, ReminderSettingsCommand.channel(3L, true, UUID.randomUUID()));

        memberships(List.of(handler)).transferAdministration(A, GUEST);

        assertThat(jdbc.queryForObject("select role from space_memberships where user_id = ?", String.class, GUEST))
                .isEqualTo("ADMINISTRATOR");
        assertThat(jdbc.queryForMap("select revoked_by_user_id, revocation_reason from whatsapp_consents"))
                .containsEntry("revoked_by_user_id", ADMIN).containsEntry("revocation_reason", "ADMINISTRATION_TRANSFERRED");
        var newAdministrator = settings.view(G);
        assertThat(newAdministrator.canManage()).isTrue();
        assertThat(newAdministrator.version()).isEqualTo(5);
        assertThat(newAdministrator.whatsapp().hasRecipient()).isFalse();
        assertThat(newAdministrator.whatsapp().enabled()).isFalse();
        assertThat(newAdministrator.whatsapp().consent().active()).isFalse();
        assertThat(newAdministrator.schedule().firstTime()).isEqualTo("08:30");
        assertThat(settings.view(A).canManage()).isFalse();
        assertThatThrownBy(() -> settings.changeChannel(A, ReminderSettingsCommand.channel(5L, true, UUID.randomUUID())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> settings.changeChannel(G, ReminderSettingsCommand.channel(5L, true, UUID.randomUUID())))
                .isInstanceOf(WhatsAppActivationRequiredException.class);
        assertThat(events()).containsExactly("SCHEDULE_CHANGED", "RECIPIENT_CHANGED", "CONSENT_GRANTED",
                "CHANNEL_ENABLED", "RECIPIENT_CHANGED", "CONSENT_REVOKED", "CHANNEL_DISABLED");

        settings.changeRecipient(G, ReminderSettingsCommand.recipient(5L, N2, UUID.randomUUID()));
        settings.grantConsent(G, ReminderSettingsCommand.consent(6L, N2, true, UUID.randomUUID()));
        assertThat(jdbc.queryForObject("select user_id from whatsapp_consents where revoked_at is null", UUID.class))
                .isEqualTo(GUEST);
    }

    @Test
    void c13FailedTransferKeepsRolesAndSettings() {
        enabledWithConsent();
        AdministrationTransferHandler failing = (space, previous, next, at) -> {
            throw new IllegalStateException("simulated failure after the consent revocation");
        };
        assertThatThrownBy(() -> memberships(List.of(handler, failing)).transferAdministration(A, GUEST))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select role from space_memberships where user_id = ?", String.class, ADMIN))
                .isEqualTo("ADMINISTRATOR");
        var view = settings.view(A);
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.whatsapp().enabled()).isTrue();
        assertThat(view.whatsapp().consent().active()).isTrue();
        assertThat(count("membership_lifecycle_events")).isZero();
    }

    @Test
    void c14RepetitionAndConcurrentConsentsNeverDuplicate() throws Exception {
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        var key = UUID.randomUUID();
        var first = settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key));
        var replay = settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key));
        assertThat(replay.version()).isEqualTo(first.version()).isEqualTo(2);
        assertThat(count("whatsapp_consents")).isOne();
        assertThatThrownBy(() -> settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N2, true, key)))
                .isInstanceOf(ReminderSettingsIdempotencyConflictException.class);

        settings.revokeConsent(A, ReminderSettingsCommand.revocation(2L, UUID.randomUUID()));
        var results = parallel(
                () -> settings.grantConsent(A, ReminderSettingsCommand.consent(3L, N1, true, UUID.randomUUID())),
                () -> settings.grantConsent(A, ReminderSettingsCommand.consent(3L, N1, true, UUID.randomUUID())));
        assertThat(results).filteredOn(result -> result instanceof ReminderSettingsView).hasSize(1);
        assertThat(results).filteredOn(result -> result instanceof ReminderSettingsVersionConflictException).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from whatsapp_consents where revoked_at is null", Integer.class))
                .isOne();

        var sameKey = UUID.randomUUID();
        var replays = parallel(
                () -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, sameKey)),
                () -> settings.changeChannel(A, ReminderSettingsCommand.channel(4L, true, sameKey)));
        assertThat(replays).allSatisfy(result -> assertThat(result).isInstanceOf(ReminderSettingsView.class));
        assertThat(settings.view(A).version()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from reminder_settings_events where event_type = 'CHANNEL_ENABLED'",
                Integer.class)).isOne();

        assertThatThrownBy(() -> jdbc.update("""
                insert into whatsapp_consents(id, space_id, user_id, recipient, consent_text_version, granted_at)
                values (?, ?, ?, '+5511987654321', 'WHATSAPP-RESUMOS-V1', ?)
                """, UUID.randomUUID(), SPACE, ADMIN, Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void c15StaleVersionIsAConflict() {
        settings.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", UUID.randomUUID()));
        assertThatThrownBy(() -> settings.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "07:00", "19:00",
                UUID.randomUUID()))).isInstanceOf(ReminderSettingsVersionConflictException.class);
        assertThat(settings.view(A).schedule().firstTime()).isEqualTo("08:30");
        assertThat(count("reminder_settings_events")).isOne();
        assertThat(count("reminder_settings_requests")).isOne();
    }

    @Test
    void c16AuditAndStorageNeverHoldTheFullNumberOutsideTheSettingsAndConsent() {
        enabledWithConsent();
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(3L, N2, UUID.randomUUID()));
        memberships(List.of(handler)).transferAdministration(A, GUEST);
        assertThat(jdbc.queryForList("select detail from reminder_settings_events", String.class))
                .isNotEmpty().allSatisfy(detail -> {
                    assertThat(detail).doesNotContain("98765").doesNotContain("99876");
                });
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name in "
                + "('reminder_settings', 'whatsapp_consents', 'reminder_settings_events')", String.class))
                .noneMatch(column -> column.contains("token") || column.contains("secret") || column.contains("password"));
    }

    private void enabledWithConsent() {
        settings.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, UUID.randomUUID()));
        settings.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, UUID.randomUUID()));
        settings.changeChannel(A, ReminderSettingsCommand.channel(2L, true, UUID.randomUUID()));
    }

    private MembershipManagementUseCase memberships(List<AdministrationTransferHandler> handlers) {
        return IdentityTestFixtures.memberships(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC), List.of(), handlers);
    }

    private List<Object> parallel(Callable<Object> first, Callable<Object> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Object>>();
            for (var task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException error) {
                        return error;
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<String> events() {
        return jdbc.queryForList("select event_type from reminder_settings_events order by to_version, occurred_at, "
                + "case event_type when 'SCHEDULE_CHANGED' then 0 when 'RECIPIENT_CHANGED' then 1 when 'CONSENT_GRANTED' "
                + "then 2 when 'CHANNEL_ENABLED' then 3 when 'CONSENT_REVOKED' then 4 else 5 end", String.class);
    }

    private int count(String table) {
        var value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
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
