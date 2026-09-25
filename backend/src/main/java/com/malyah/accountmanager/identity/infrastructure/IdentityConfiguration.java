package com.malyah.accountmanager.identity.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import com.malyah.accountmanager.identity.application.InitialSetupService;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.InitialSetupRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;
import com.malyah.accountmanager.identity.application.AccountAccessService;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.LoginService;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.port.AccessEmailSender;
import com.malyah.accountmanager.identity.application.port.AccessTokenCodec;
import com.malyah.accountmanager.identity.application.port.AccountAccessRepository;
import com.malyah.accountmanager.identity.application.port.CredentialsRepository;
import com.malyah.accountmanager.identity.application.port.PasswordVerifier;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;
import com.malyah.accountmanager.identity.application.InvitationService;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.port.InvitationEmailSender;
import com.malyah.accountmanager.identity.application.port.InvitationRepository;
import com.malyah.accountmanager.identity.application.MembershipManagementService;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.MembershipRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
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
    PasswordVerifier passwordVerifier(PasswordHasher passwordHasher) {
        return (PasswordVerifier) passwordHasher;
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    JdbcAccountAccessRepository jdbcAccountAccessRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAccountAccessRepository(jdbcTemplate);
    }

    @Bean
    AccessTokenCodec accessTokenCodec() {
        return new SecureAccessTokenCodec();
    }

    @Bean
    AccessEmailSender accessEmailSender(
            JavaMailSender mailSender,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.mail.from}") String from) {
        return new SmtpAccessEmailSender(mailSender, publicBaseUrl, from);
    }

    @Bean
    InvitationRepository invitationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInvitationRepository(jdbcTemplate);
    }

    @Bean
    MembershipRepository membershipRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMembershipRepository(jdbcTemplate);
    }

    @Bean
    MembershipManagementUseCase membershipManagementUseCase(
            MembershipRepository repository,
            SessionRevoker sessionRevoker,
            IdentifierGenerator identifierGenerator,
            Clock applicationClock,
            PlatformTransactionManager transactionManager) {
        var service = new MembershipManagementService(
                repository, sessionRevoker, identifierGenerator, applicationClock);
        return new TransactionalMembershipManagementUseCase(
                service, new TransactionTemplate(transactionManager));
    }

    @Bean
    InvitationEmailSender invitationEmailSender(
            JavaMailSender mailSender,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.mail.from}") String from) {
        return new SmtpInvitationEmailSender(mailSender, publicBaseUrl, from);
    }

    @Bean
    InvitationUseCase invitationUseCase(
            InvitationRepository repository,
            AccessTokenCodec tokenCodec,
            IdentifierGenerator identifierGenerator,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            Clock applicationClock,
            InvitationEmailSender emailSender,
            PlatformTransactionManager transactionManager) {
        var service = new InvitationService(
                repository, tokenCodec, identifierGenerator, passwordHasher, passwordPolicy, applicationClock);
        return new TransactionalInvitationUseCase(
                service, emailSender, new TransactionTemplate(transactionManager));
    }

    @Bean
    AccountAccessUseCase accountAccessUseCase(
            AccountAccessRepository repository,
            AccessTokenCodec tokenCodec,
            IdentifierGenerator identifierGenerator,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            Clock applicationClock,
            AccessEmailSender emailSender,
            PlatformTransactionManager transactionManager) {
        var service = new AccountAccessService(
                repository, tokenCodec, identifierGenerator, passwordHasher, passwordPolicy, applicationClock);
        return new TransactionalAccountAccessUseCase(
                service, emailSender, new TransactionTemplate(transactionManager));
    }

    @Bean
    LoginUseCase loginUseCase(CredentialsRepository repository, PasswordVerifier passwordVerifier) {
        return new LoginService(repository, passwordVerifier);
    }

    @Bean
    SessionRevoker sessionRevoker(JdbcTemplate jdbcTemplate) {
        return new JdbcSessionRevoker(jdbcTemplate);
    }

    @Bean
    CookieSerializer sessionCookieSerializer(Environment environment) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie("production".equalsIgnoreCase(
                environment.getProperty("APP_ENVIRONMENT", "local")));
        serializer.setCookieMaxAge(Math.toIntExact(java.time.Duration.ofDays(30).toSeconds()));
        return serializer;
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
