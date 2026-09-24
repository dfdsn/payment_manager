package com.malyah.accountmanager.identity.application;

import java.time.Clock;
import java.time.ZoneId;

import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.InitialSetupRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;
import com.malyah.accountmanager.identity.domain.AdministratorName;
import com.malyah.accountmanager.identity.domain.EmailAddress;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;
import com.malyah.accountmanager.identity.domain.SpaceName;

public final class InitialSetupService implements InitialSetupUseCase {

    static final String DEFAULT_CURRENCY = "BRL";
    static final String DEFAULT_LOCALE = "pt-BR";
    static final String DEFAULT_TIME_ZONE = "America/Sao_Paulo";

    private final InitialSetupRepository repository;
    private final SetupSecretVerifier secretVerifier;
    private final PasswordHasher passwordHasher;
    private final IdentifierGenerator identifierGenerator;
    private final Clock clock;
    private final PasswordPolicy passwordPolicy;

    public InitialSetupService(
            InitialSetupRepository repository,
            SetupSecretVerifier secretVerifier,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            Clock clock,
            PasswordPolicy passwordPolicy) {
        this.repository = repository;
        this.secretVerifier = secretVerifier;
        this.passwordHasher = passwordHasher;
        this.identifierGenerator = identifierGenerator;
        this.clock = clock;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    public InitialSetupStatus status() {
        if (repository.isCompleted()) {
            return InitialSetupStatus.COMPLETED;
        }
        return secretVerifier.isConfigured()
                ? InitialSetupStatus.AVAILABLE
                : InitialSetupStatus.SECRET_NOT_CONFIGURED;
    }

    @Override
    public InitialSetupResult configure(InitialSetupCommand command) {
        if (repository.isCompleted()) {
            throw new SetupAlreadyCompletedException();
        }
        if (!secretVerifier.isConfigured()) {
            throw new SetupSecretUnavailableException();
        }
        if (!secretVerifier.matches(command.setupSecret())) {
            throw new InvalidSetupSecretException();
        }

        var administratorName = new AdministratorName(command.administratorName());
        var email = new EmailAddress(command.email());
        var spaceName = new SpaceName(command.spaceName());
        passwordPolicy.validate(command.password());
        var passwordHash = passwordHasher.hash(command.password());

        if (repository.lockAndCheckCompleted()) {
            throw new SetupAlreadyCompletedException();
        }

        var registration = new InitialSetupRegistration(
                identifierGenerator.next(),
                administratorName.value(),
                email.value(),
                passwordHash,
                identifierGenerator.next(),
                spaceName.value(),
                DEFAULT_CURRENCY,
                DEFAULT_LOCALE,
                ZoneId.of(DEFAULT_TIME_ZONE).getId(),
                identifierGenerator.next(),
                clock.instant());
        repository.create(registration);
        repository.markCompleted(registration);

        return new InitialSetupResult(
                registration.administratorId(),
                registration.administratorName(),
                registration.normalizedEmail(),
                registration.spaceId(),
                registration.spaceName(),
                registration.currency(),
                registration.locale(),
                registration.timeZone());
    }
}
