package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.InitialSetupRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

class InitialSetupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T20:00:00Z");
    private final FakeRepository repository = new FakeRepository();
    private final MutableSecretVerifier secret = new MutableSecretVerifier(true, true);
    private final Queue<UUID> ids = new ArrayDeque<>();

    @Test
    void exposesSetupStatusWithoutOpeningCompletedInstallation() {
        var service = service();
        assertThat(service.status()).isEqualTo(InitialSetupStatus.AVAILABLE);

        secret.configured = false;
        assertThat(service.status()).isEqualTo(InitialSetupStatus.SECRET_NOT_CONFIGURED);

        repository.completed = true;
        assertThat(service.status()).isEqualTo(InitialSetupStatus.COMPLETED);
    }

    @Test
    void createsNormalizedAdministratorAndSpaceWithRequiredDefaults() {
        var administratorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var spaceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var membershipId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        ids.add(administratorId);
        ids.add(spaceId);
        ids.add(membershipId);
        var password = "frase segura 2026".toCharArray();

        var result = service().configure(new InitialSetupCommand(
                "setup-secret", "  Diego   Silva ", " DIEGO@EXAMPLE.COM ", password, " Minha   casa "));

        assertThat(result).isEqualTo(new InitialSetupResult(
                administratorId, "Diego Silva", "diego@example.com", spaceId, "Minha casa",
                "BRL", "pt-BR", "America/Sao_Paulo"));
        assertThat(repository.created.passwordHash()).isEqualTo("{test}hash");
        assertThat(repository.created.membershipId()).isEqualTo(membershipId);
        assertThat(repository.created.createdAt()).isEqualTo(NOW);
        assertThat(repository.marked).isTrue();
    }

    @Test
    void rejectsMissingOrInvalidSecretWithoutWriting() {
        secret.configured = false;
        assertThatThrownBy(() -> service().configure(command()))
                .isInstanceOf(SetupSecretUnavailableException.class);

        secret.configured = true;
        secret.matches = false;
        assertThatThrownBy(() -> service().configure(command()))
                .isInstanceOf(InvalidSetupSecretException.class);

        assertThat(repository.created).isNull();
    }

    @Test
    void rejectsCompletedSetupBeforeProcessingAndAgainAfterDatabaseLock() {
        repository.completed = true;
        assertThatThrownBy(() -> service().configure(command()))
                .isInstanceOf(SetupAlreadyCompletedException.class);

        repository.completed = false;
        repository.completedWhenLocked = true;
        assertThatThrownBy(() -> service().configure(command()))
                .isInstanceOf(SetupAlreadyCompletedException.class);
        assertThat(repository.created).isNull();
    }

    @Test
    void commandNeverPrintsSecretOrPassword() {
        assertThat(command().toString()).isEqualTo("InitialSetupCommand[protected]");
    }

    private InitialSetupCommand command() {
        return new InitialSetupCommand(
                "setup-secret", "Administrador", "admin@example.com", "frase segura 2026".toCharArray(), "Minha casa");
    }

    private InitialSetupService service() {
        IdentifierGenerator generator = () -> ids.isEmpty() ? UUID.randomUUID() : ids.remove();
        PasswordHasher hasher = password -> "{test}hash";
        return new InitialSetupService(
                repository,
                secret,
                hasher,
                generator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PasswordPolicy());
    }

    private static final class MutableSecretVerifier implements SetupSecretVerifier {
        private boolean configured;
        private boolean matches;

        private MutableSecretVerifier(boolean configured, boolean matches) {
            this.configured = configured;
            this.matches = matches;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public boolean matches(String presentedSecret) {
            return matches && "setup-secret".equals(presentedSecret);
        }
    }

    private static final class FakeRepository implements InitialSetupRepository {
        private boolean completed;
        private boolean completedWhenLocked;
        private boolean marked;
        private InitialSetupRegistration created;

        @Override
        public boolean isCompleted() {
            return completed;
        }

        @Override
        public boolean lockAndCheckCompleted() {
            return completedWhenLocked;
        }

        @Override
        public void create(InitialSetupRegistration registration) {
            created = registration;
        }

        @Override
        public void markCompleted(InitialSetupRegistration registration) {
            marked = registration == created;
        }
    }
}
