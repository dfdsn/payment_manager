package com.malyah.accountmanager.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.env.Environment;

import com.malyah.accountmanager.identity.application.port.SetupSecretVerifier;

final class EnvironmentSetupSecretVerifier implements SetupSecretVerifier {

    private final String configuredSecret;

    EnvironmentSetupSecretVerifier(Environment environment) {
        this.configuredSecret = environment.getProperty("app.setup.secret");
    }

    @Override
    public boolean isConfigured() {
        return configuredSecret != null && !configuredSecret.isBlank();
    }

    @Override
    public boolean matches(String presentedSecret) {
        if (!isConfigured() || presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(digest(configuredSecret), digest(presentedSecret));
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível.", exception);
        }
    }
}
