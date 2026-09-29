package com.malyah.accountmanager.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.InvalidInvitationTokenException;
import com.malyah.accountmanager.identity.application.InvitationAcceptanceCommand;
import com.malyah.accountmanager.identity.application.InvitationEmailDeliveryException;
import com.malyah.accountmanager.identity.application.InvitationService;
import com.malyah.accountmanager.identity.application.PendingInvitationEmail;
import com.malyah.accountmanager.identity.application.SpaceMemberLimitReachedException;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;
import com.malyah.accountmanager.identity.domain.SpaceRole;

@Testcontainers
class InvitationPostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_invitation_test")
            .withUsername("account_manager")
            .withPassword("test-only-password");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ArrayList<PendingInvitationEmail> sent;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(25);
        jdbc = new JdbcTemplate(dataSource);
        sent = new ArrayList<>();
        insertAdministrator();
    }

    @Test
    void invitationIsHashedResendInvalidatesOldTokenAndAcceptanceCreatesIsolatedGuest() {
        var useCase = useCase(sent::add);
        useCase.invite("admin@example.com", "GUEST@example.com");
        var oldToken = sent.getLast().rawToken();

        assertThat(jdbc.queryForList("select token_hash from space_invitations", String.class))
                .noneMatch(hash -> hash.contains(oldToken));
        useCase.resend("admin@example.com");
        var token = sent.getLast().rawToken();
        assertThat(token).isNotEqualTo(oldToken);
        assertThatThrownBy(() -> useCase.preview(oldToken, null))
                .isInstanceOf(InvalidInvitationTokenException.class);

        useCase.accept(new InvitationAcceptanceCommand(
                token, null, "Pessoa Convidada", "senha segura 2026".toCharArray()));

        assertThat(jdbc.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active = true",
                Integer.class, SPACE_ID)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select m.role from space_memberships m
                join identity_users u on u.id = m.user_id
                where u.normalized_email = 'guest@example.com'
                """, String.class)).isEqualTo("GUEST");
        assertThat(jdbc.queryForObject(
                "select email_confirmed from identity_users where normalized_email = 'guest@example.com'",
                Boolean.class)).isTrue();

        var guest = new AuthenticatedUserContextService(new JdbcAuthenticatedUserContextRepository(jdbc))
                .findByEmail("guest@example.com");
        assertThat(guest.spaceId()).isEqualTo(SPACE_ID);
        assertThat(guest.role()).isEqualTo(SpaceRole.GUEST);
        assertThatThrownBy(() -> useCase.accept(new InvitationAcceptanceCommand(
                token, null, "Pessoa Convidada", "senha segura 2026".toCharArray())))
                .isInstanceOf(InvalidInvitationTokenException.class);
        assertThatThrownBy(() -> useCase.invite("admin@example.com", "third@example.com"))
                .isInstanceOf(SpaceMemberLimitReachedException.class);
    }

    @Test
    void concurrentAcceptanceCreatesExactlyOneGuestAndConsumesTokenOnce() throws Exception {
        var captured = Collections.synchronizedList(new ArrayList<PendingInvitationEmail>());
        var useCase = useCase(captured::add);
        useCase.invite("admin@example.com", "guest@example.com");
        var token = captured.getFirst().rawToken();
        Callable<Boolean> acceptance = () -> {
            try {
                useCase.accept(new InvitationAcceptanceCommand(
                        token, null, "Pessoa Convidada", "senha segura 2026".toCharArray()));
                return true;
            } catch (InvalidInvitationTokenException exception) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(java.util.List.of(acceptance, acceptance));
            assertThat(results).extracting(future -> future.get()).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active = true",
                Integer.class, SPACE_ID)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from space_invitations where consumed_at is not null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void emailFailureKeepsPendingInvitationAvailableForExplicitResend() {
        var failing = useCase(email -> { throw new IllegalStateException("simulated SMTP failure"); });

        assertThatThrownBy(() -> failing.invite("admin@example.com", "guest@example.com"))
                .isInstanceOf(InvitationEmailDeliveryException.class);
        assertThat(failing.current("admin@example.com")).isPresent();
        assertThat(jdbc.queryForObject(
                "select count(*) from space_invitations where revoked_at is null and consumed_at is null",
                Integer.class)).isEqualTo(1);
    }

    private TransactionalInvitationUseCase useCase(
            com.malyah.accountmanager.identity.application.port.InvitationEmailSender emailSender) {
        var repository = new JdbcInvitationRepository(jdbc);
        var service = new InvitationService(
                repository,
                new SecureAccessTokenCodec(),
                UUID::randomUUID,
                password -> "{test}" + new String(password),
                new PasswordPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new TransactionalInvitationUseCase(
                service, emailSender, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private void insertAdministrator() {
        jdbc.update("""
                insert into identity_users(id, display_name, normalized_email, password_hash, email_confirmed, created_at)
                values (?, 'Administrador', 'admin@example.com', '{test}senha segura 2026', true, ?)
                """, ADMIN_ID, Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into family_spaces(id, name, currency_code, locale, time_zone, created_at)
                values (?, 'Minha casa', 'BRL', 'pt-BR', 'America/Sao_Paulo', ?)
                """, SPACE_ID, Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, 'ADMINISTRATOR', true, ?)
                """, UUID.randomUUID(), ADMIN_ID, SPACE_ID, Timestamp.from(NOW.minusSeconds(60)));
    }
}
