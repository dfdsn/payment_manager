package com.malyah.accountmanager.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.MembershipAdministratorRequiredException;
import com.malyah.accountmanager.identity.application.MembershipConflictException;
import com.malyah.accountmanager.identity.application.MembershipManagementService;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.domain.SpaceRole;

@Testcontainers
class MembershipPostgresIT {
    private static final Instant NOW = Instant.parse("2026-09-25T15:00:00Z");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_membership_test")
            .withUsername("account_manager")
            .withPassword("test-only-password");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private MembershipManagementUseCase useCase;

    @BeforeEach
    void reset() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(27);
        jdbc = new JdbcTemplate(dataSource);
        insertSpaceAndMembers();
        var repository = new JdbcMembershipRepository(jdbc);
        var service = new MembershipManagementService(
                repository, new JdbcSessionRevoker(jdbc), UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(new com.malyah.accountmanager.expenses.infrastructure.JdbcMembershipDepartureHandler(jdbc)));
        useCase = new TransactionalMembershipManagementUseCase(
                service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void removalRevokesOpenSessionsPreservesHistoryAndReleasesInvitationVacancy() {
        insertSession("guest-session", "guest@example.com");
        var expense = UUID.randomUUID();
        jdbc.update("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed,
                    status, due_date, reference_date, responsible_user_id, created_by_user_id, created_at, version)
                values (?, ?, 'ONE_OFF', 'Internet', 120.00, true, 'PENDING', '2026-10-05', '2026-10-05', ?, ?, ?, 0)
                """, expense, SPACE, GUEST, ADMIN, Timestamp.from(NOW.minusSeconds(30)));

        useCase.remove("admin@example.com", GUEST);

        assertThat(jdbc.queryForObject("select active from space_memberships where user_id = ?", Boolean.class, GUEST))
                .isFalse();
        assertThat(jdbc.queryForObject("select end_reason from space_memberships where user_id = ?", String.class, GUEST))
                .isEqualTo("ADMIN_REMOVAL");
        assertThat(jdbc.queryForObject("select count(*) from identity_users where id = ?", Integer.class, GUEST))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class,
                "guest@example.com")).isZero();
        assertThat(jdbc.queryForObject("select event_type from membership_lifecycle_events", String.class))
                .isEqualTo("MEMBER_REMOVED");
        assertThat(jdbc.queryForObject("select responsible_user_id from expense_entries where id=?", UUID.class, expense))
                .isNull();
        assertThat(jdbc.queryForObject("select version from expense_entries where id=?", Long.class, expense)).isOne();
        assertThat(jdbc.queryForObject("select old_responsible_name from expense_correction_events where expense_id=?",
                String.class, expense)).isEqualTo("Convidado");
        assertThat(jdbc.queryForObject("select actor_user_id from expense_correction_events where expense_id=?",
                UUID.class, expense)).isEqualTo(ADMIN);
        assertThatThrownBy(() -> new AuthenticatedUserContextService(new JdbcAuthenticatedUserContextRepository(jdbc))
                .findByEmail("guest@example.com"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);

        var invitation = invitationService();
        assertThat(invitation.prepareNew("admin@example.com", "next@example.com").recipient())
                .isEqualTo("next@example.com");
    }

    @Test
    void transferInBothDirectionsUpdatesLivePermissionsThenGuestCanLeave() {
        insertSession("admin-session", "admin@example.com");
        insertSession("guest-session", "guest@example.com");
        useCase.transferAdministration("admin@example.com", GUEST);

        assertThat(role(ADMIN)).isEqualTo("GUEST");
        assertThat(role(GUEST)).isEqualTo("ADMINISTRATOR");
        assertThatThrownBy(() -> useCase.remove("admin@example.com", GUEST))
                .isInstanceOf(MembershipAdministratorRequiredException.class);
        assertThat(new AuthenticatedUserContextService(new JdbcAuthenticatedUserContextRepository(jdbc))
                .findByEmail("guest@example.com").role()).isEqualTo(SpaceRole.ADMINISTRATOR);

        useCase.transferAdministration("guest@example.com", ADMIN);
        assertThat(role(ADMIN)).isEqualTo("ADMINISTRATOR");
        assertThat(role(GUEST)).isEqualTo("GUEST");
        useCase.leave("guest@example.com");
        assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class,
                "guest@example.com")).isZero();
        assertThat(jdbc.queryForList("select event_type from membership_lifecycle_events order by occurred_at, event_type",
                String.class)).containsExactlyInAnyOrder(
                        "ADMINISTRATION_TRANSFERRED", "ADMINISTRATION_TRANSFERRED", "MEMBER_LEFT");
        assertThat(jdbc.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active and role = 'ADMINISTRATOR'",
                Integer.class, SPACE)).isOne();
    }

    @Test
    void concurrentExitAndTransferSerializeWithoutOrphaningTheSpace() throws Exception {
        Callable<String> transfer = () -> outcome(() -> useCase.transferAdministration("admin@example.com", GUEST));
        Callable<String> leave = () -> outcome(() -> useCase.leave("guest@example.com"));

        List<String> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            outcomes = executor.invokeAll(List.of(transfer, leave)).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();
        }

        assertThat(outcomes).contains("SUCCESS").anyMatch(value -> value.startsWith("CONFLICT:"));
        assertThat(jdbc.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active and role = 'ADMINISTRATOR'",
                Integer.class, SPACE)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active",
                Integer.class, SPACE)).isBetween(1, 2);
    }

    private String outcome(Runnable operation) {
        try {
            operation.run();
            return "SUCCESS";
        } catch (MembershipConflictException | MembershipAdministratorRequiredException exception) {
            return "CONFLICT:" + exception.getClass().getSimpleName();
        }
    }

    private com.malyah.accountmanager.identity.application.InvitationService invitationService() {
        return new com.malyah.accountmanager.identity.application.InvitationService(
                new JdbcInvitationRepository(jdbc), new SecureAccessTokenCodec(), UUID::randomUUID,
                password -> "{test}" + new String(password),
                new com.malyah.accountmanager.identity.domain.PasswordPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String role(UUID userId) {
        return jdbc.queryForObject(
                "select role from space_memberships where user_id = ? and active", String.class, userId);
    }

    private void insertSpaceAndMembers() {
        jdbc.update("""
                insert into family_spaces(id, name, currency_code, locale, time_zone, created_at)
                values (?, 'Casa', 'BRL', 'pt-BR', 'America/Sao_Paulo', ?)
                """, SPACE, Timestamp.from(NOW.minusSeconds(60)));
        insertUser(ADMIN, "Administrador", "admin@example.com", "ADMINISTRATOR");
        insertUser(GUEST, "Convidado", "guest@example.com", "GUEST");
    }

    private void insertUser(UUID id, String name, String email, String role) {
        jdbc.update("""
                insert into identity_users(id, display_name, normalized_email, password_hash, email_confirmed, created_at)
                values (?, ?, ?, '{test}senha segura 2026', true, ?)
                """, id, name, email, Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, ?, true, ?)
                """, UUID.randomUUID(), id, SPACE, role, Timestamp.from(NOW.minusSeconds(60)));
    }

    private void insertSession(String id, String principal) {
        jdbc.update("""
                insert into spring_session(primary_id, session_id, creation_time, last_access_time,
                    max_inactive_interval, expiry_time, principal_name)
                values (?, ?, 1, 1, 1800, 9999999999999, ?)
                """, id, id, principal);
    }
}
