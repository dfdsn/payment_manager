package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

class AuthenticatedUserContextServiceTest {

    @Test
    void normalizesPrincipalEmailAndReturnsActiveSpaceContext() {
        var expected = context();
        AuthenticatedUserContextRepository repository = email -> {
            assertThat(email).isEqualTo("admin@example.com");
            return Optional.of(expected);
        };

        assertThat(new AuthenticatedUserContextService(repository).findByEmail(" ADMIN@EXAMPLE.COM "))
                .isSameAs(expected);
    }

    @Test
    void refusesAuthenticatedPrincipalWithoutActiveSpace() {
        AuthenticatedUserContextRepository repository = email -> Optional.empty();

        assertThatThrownBy(() -> new AuthenticatedUserContextService(repository).findByEmail("admin@example.com"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    private AuthenticatedUserContext context() {
        return new AuthenticatedUserContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Administrador",
                "admin@example.com",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Minha casa",
                SpaceRole.ADMINISTRATOR,
                "BRL",
                "pt-BR",
                "America/Sao_Paulo");
    }
}
