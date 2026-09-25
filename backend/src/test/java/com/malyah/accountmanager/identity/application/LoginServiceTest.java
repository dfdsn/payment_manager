package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.port.CredentialsRepository;
import com.malyah.accountmanager.identity.application.port.PasswordVerifier;

class LoginServiceTest {

    private final CredentialsRepository repository = mock(CredentialsRepository.class);
    private final PasswordVerifier verifier = mock(PasswordVerifier.class);
    private final LoginService service = new LoginService(repository, verifier);

    @Test
    void authenticatesConfirmedActiveAccountAndErasesPasswordBuffer() {
        var password = "frase segura 2026".toCharArray();
        given(repository.findActiveByNormalizedEmail("admin@example.com"))
                .willReturn(Optional.of(new LoginCredentials("admin@example.com", "hash", true)));
        given(verifier.matches(password, "hash")).willReturn(true);

        assertThat(service.authenticate(" ADMIN@EXAMPLE.COM ", password)).isEqualTo("admin@example.com");
        assertThat(password).containsOnly('\0');
    }

    @Test
    void usesSameGenericFailureForUnknownUnconfirmedWrongPasswordAndInvalidEmail() {
        given(repository.findActiveByNormalizedEmail("unknown@example.com")).willReturn(Optional.empty());
        given(repository.findActiveByNormalizedEmail("pending@example.com"))
                .willReturn(Optional.of(new LoginCredentials("pending@example.com", "hash", false)));
        given(repository.findActiveByNormalizedEmail("admin@example.com"))
                .willReturn(Optional.of(new LoginCredentials("admin@example.com", "hash", true)));

        assertInvalid("unknown@example.com", "senha");
        assertInvalid("pending@example.com", "senha");
        assertInvalid("admin@example.com", "senha");
        assertInvalid("email-invalido", "senha");
    }

    private void assertInvalid(String email, String password) {
        assertThatThrownBy(() -> service.authenticate(email, password.toCharArray()))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Email ou senha inválidos.");
    }
}
