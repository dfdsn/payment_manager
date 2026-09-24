package com.malyah.accountmanager.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityAdaptersTest {

    @Test
    void comparesConfiguredSecretAndRejectsMissingValues() {
        var missing = new EnvironmentSetupSecretVerifier(new MockEnvironment());
        assertThat(missing.isConfigured()).isFalse();
        assertThat(missing.matches(null)).isFalse();

        var configured = new EnvironmentSetupSecretVerifier(
                new MockEnvironment().withProperty("app.setup.secret", "temporario-seguro"));
        assertThat(configured.isConfigured()).isTrue();
        assertThat(configured.matches("temporario-seguro")).isTrue();
        assertThat(configured.matches("outro")).isFalse();
    }

    @Test
    void hashesPasswordWithBcryptCostTwelveAndClearsInput() {
        var raw = "frase segura 2026".toCharArray();
        var encoded = new BcryptPasswordHasher().hash(raw);

        assertThat(encoded).startsWith("{bcrypt}$2");
        assertThat(new BCryptPasswordEncoder(12).matches(
                "frase segura 2026", encoded.substring("{bcrypt}".length()))).isTrue();
        assertThat(raw).containsOnly('\0');
    }
}
