package com.malyah.accountmanager.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.malyah.accountmanager.identity.application.InitialSetupCommand;
import com.malyah.accountmanager.identity.application.InitialSetupResult;
import com.malyah.accountmanager.identity.application.InitialSetupService;
import com.malyah.accountmanager.identity.application.InitialSetupStatus;
import com.malyah.accountmanager.identity.application.SetupAlreadyCompletedException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

@Testcontainers
class InitialSetupPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_identity_test")
            .withUsername("account_manager")
            .withPassword("test-only-password");

    @Test
    void persistsOnlyOneAdministratorAcrossConcurrencyAndRestart() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted).isEqualTo(18);

        var jdbc = new JdbcTemplate(dataSource);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Object> attempt = () -> {
            ready.countDown();
            start.await();
            try {
                return useCase(jdbc, dataSource).configure(command());
            } catch (RuntimeException exception) {
                return exception;
            }
        };

        List<Object> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            ready.await();
            start.countDown();
            outcomes = List.of(first.get(), second.get());
        }

        assertThat(outcomes).filteredOn(InitialSetupResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(SetupAlreadyCompletedException.class::isInstance).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from identity_users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from family_spaces", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from space_memberships", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select currency_code from family_spaces", String.class)).isEqualTo("BRL");
        assertThat(jdbc.queryForObject("select locale from family_spaces", String.class)).isEqualTo("pt-BR");
        assertThat(jdbc.queryForObject("select time_zone from family_spaces", String.class))
                .isEqualTo("America/Sao_Paulo");
        assertThat(jdbc.queryForObject("select password_hash from identity_users", String.class))
                .startsWith("{test}");
        var context = new AuthenticatedUserContextService(new JdbcAuthenticatedUserContextRepository(jdbc))
                .findByEmail(" ADMIN@EXAMPLE.COM ");
        assertThat(context.displayName()).isEqualTo("Administrador");
        assertThat(context.role().name()).isEqualTo("ADMINISTRATOR");
        assertThat(context.spaceName()).isEqualTo("Minha casa");

        var afterRestart = useCase(jdbc, dataSource);
        assertThat(afterRestart.status()).isEqualTo(InitialSetupStatus.COMPLETED);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> afterRestart.configure(command())))
                .isInstanceOf(SetupAlreadyCompletedException.class);
    }

    private TransactionalInitialSetupUseCase useCase(
            JdbcTemplate jdbc, DriverManagerDataSource dataSource) {
        var repository = new JdbcInitialSetupRepository(jdbc);
        SetupSecretVerifier secret = new SetupSecretVerifier() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public boolean matches(String presentedSecret) {
                return "test-secret".equals(presentedSecret);
            }
        };
        var service = new InitialSetupService(
                repository,
                secret,
                password -> "{test}" + new String(password),
                UUID::randomUUID,
                Clock.fixed(Instant.parse("2026-09-23T20:00:00Z"), ZoneOffset.UTC),
                new PasswordPolicy());
        return new TransactionalInitialSetupUseCase(
                service,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private InitialSetupCommand command() {
        return new InitialSetupCommand(
                "test-secret",
                "Administrador",
                "admin@example.com",
                "frase segura 2026".toCharArray(),
                "Minha casa");
    }
}
