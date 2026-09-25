package com.malyah.accountmanager.identity.infrastructure;

import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.application.port.PasswordVerifier;

final class BcryptPasswordHasher implements PasswordHasher, PasswordVerifier {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(char[] password) {
        try {
            return "{bcrypt}" + encoder.encode(new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Override
    public boolean matches(char[] rawPassword, String passwordHash) {
        var encoded = passwordHash != null && passwordHash.startsWith("{bcrypt}")
                ? passwordHash.substring("{bcrypt}".length())
                : passwordHash;
        return encoded != null && encoder.matches(new String(rawPassword), encoded);
    }
}
