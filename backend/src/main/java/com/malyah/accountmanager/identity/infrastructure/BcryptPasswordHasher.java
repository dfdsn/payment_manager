package com.malyah.accountmanager.identity.infrastructure;

import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.malyah.accountmanager.identity.application.port.PasswordHasher;

final class BcryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(char[] password) {
        try {
            return "{bcrypt}" + encoder.encode(new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
