package com.malyah.accountmanager.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.malyah.accountmanager.identity.application.AccountAccessService;
import com.malyah.accountmanager.identity.application.InvalidOrExpiredAccessTokenException;
import com.malyah.accountmanager.identity.application.PendingAccessEmail;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

@Testcontainers
class AccountAccessPostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_access_test")
            .withUsername("account_manager")
            .withPassword("test-only-password");

    @Test
    void persistsSingleUseTokensInvalidatesResendAndRevokesSessionsOnReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
        var jdbc = new JdbcTemplate(dataSource);
        insertAccount(jdbc);
        var sent = new ArrayList<PendingAccessEmail>();
        var useCase = useCase(jdbc, dataSource, sent, NOW);

        useCase.requestEmailConfirmation("admin@example.com");
        var firstConfirmation = sent.getLast().rawToken();
        useCase.requestEmailConfirmation("admin@example.com");
        var secondConfirmation = sent.getLast().rawToken();

        assertThat(secondConfirmation).isNotEqualTo(firstConfirmation);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity_access_tokens
                 where purpose = 'CONFIRM_EMAIL' and revoked_at is not null
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select token_hash from identity_access_tokens", String.class))
                .noneMatch(hash -> hash.contains(firstConfirmation) || hash.contains(secondConfirmation));
        assertThatThrownBy(() -> useCase.confirmEmail(firstConfirmation))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);

        useCase.confirmEmail(secondConfirmation);
        assertThat(jdbc.queryForObject(
                "select email_confirmed from identity_users where id = ?", Boolean.class, USER_ID)).isTrue();
        assertThatThrownBy(() -> useCase.confirmEmail(secondConfirmation))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);

        insertSessions(jdbc);
        useCase.requestPasswordReset("admin@example.com");
        var resetToken = sent.getLast().rawToken();
        useCase.resetPassword(resetToken, "nova senha segura 2026");

        assertThat(jdbc.queryForObject(
                "select count(*) from spring_session where principal_name = 'admin@example.com'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select password_hash from identity_users where id = ?", String.class, USER_ID))
                .isEqualTo("{test}nova senha segura 2026");
        assertThatThrownBy(() -> useCase.resetPassword(resetToken, "outra senha segura 2026"))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
    }

    @Test
    void rejectsExpiredResetTokenPersistedInPostgres() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        var jdbc = new JdbcTemplate(dataSource);
        insertAccount(jdbc);
        var sent = new ArrayList<PendingAccessEmail>();
        var useCase = useCase(jdbc, dataSource, sent, NOW);
        useCase.requestPasswordReset("admin@example.com");
        var token = sent.getLast().rawToken();
        var afterExpiration = useCase(jdbc, dataSource, sent, NOW.plusSeconds(31 * 60));

        assertThatThrownBy(() -> afterExpiration.resetPassword(token, "nova senha segura 2026"))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
    }

    private TransactionalAccountAccessUseCase useCase(
            JdbcTemplate jdbc,
            DriverManagerDataSource dataSource,
            ArrayList<PendingAccessEmail> sent,
            Instant clockInstant) {
        var repository = new JdbcAccountAccessRepository(jdbc);
        var codec = new SecureAccessTokenCodec();
        var service = new AccountAccessService(
                repository,
                codec,
                UUID::randomUUID,
                password -> "{test}" + new String(password),
                new PasswordPolicy(),
                Clock.fixed(clockInstant, ZoneOffset.UTC));
        return new TransactionalAccountAccessUseCase(
                service, sent::add, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private void insertAccount(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into identity_users(id, display_name, normalized_email, password_hash, email_confirmed, created_at)
                values (?, 'Administrador', 'admin@example.com', '{test}senha anterior 2026', false, ?)
                """, USER_ID, Timestamp.from(NOW.minusSeconds(60)));
        var spaceId = UUID.randomUUID();
        jdbc.update("""
                insert into family_spaces(id, name, currency_code, locale, time_zone, created_at)
                values (?, 'Minha casa', 'BRL', 'pt-BR', 'America/Sao_Paulo', ?)
                """, spaceId, Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, 'ADMINISTRATOR', true, ?)
                """, UUID.randomUUID(), USER_ID, spaceId, Timestamp.from(NOW.minusSeconds(60)));
    }

    private void insertSessions(JdbcTemplate jdbc) {
        for (var index = 0; index < 2; index++) {
            var primaryId = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into spring_session(
                        primary_id, session_id, creation_time, last_access_time,
                        max_inactive_interval, expiry_time, principal_name
                    ) values (?, ?, ?, ?, ?, ?, 'admin@example.com')
                    """, primaryId, UUID.randomUUID().toString(), NOW.toEpochMilli(), NOW.toEpochMilli(),
                    604800, NOW.plusSeconds(604800).toEpochMilli());
        }
    }
}
