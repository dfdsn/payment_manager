package com.malyah.accountmanager.identity.infrastructure;

import java.time.Clock;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.InitialSetupService;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.InitialSetupRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
class IdentityConfiguration {

    @Bean
    InitialSetupRepository initialSetupRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInitialSetupRepository(jdbcTemplate);
    }

    @Bean
    AuthenticatedUserContextRepository authenticatedUserContextRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAuthenticatedUserContextRepository(jdbcTemplate);
    }

    @Bean
    AuthenticatedUserContextQuery authenticatedUserContextQuery(AuthenticatedUserContextRepository repository) {
        return new AuthenticatedUserContextService(repository);
    }

    @Bean
    SetupSecretVerifier setupSecretVerifier(Environment environment) {
        return new EnvironmentSetupSecretVerifier(environment);
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new BcryptPasswordHasher();
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    InitialSetupUseCase initialSetupUseCase(
            InitialSetupRepository repository,
            SetupSecretVerifier secretVerifier,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            Clock applicationClock,
            PasswordPolicy passwordPolicy,
            PlatformTransactionManager transactionManager) {
        var service = new InitialSetupService(
                repository,
                secretVerifier,
                passwordHasher,
                identifierGenerator,
                applicationClock,
                passwordPolicy);
        return new TransactionalInitialSetupUseCase(service, new TransactionTemplate(transactionManager));
    }
}
