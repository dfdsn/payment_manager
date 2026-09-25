package com.malyah.accountmanager.identity.application;

import java.util.Arrays;

import com.malyah.accountmanager.identity.application.port.CredentialsRepository;
import com.malyah.accountmanager.identity.application.port.PasswordVerifier;
import com.malyah.accountmanager.identity.domain.EmailAddress;
import com.malyah.accountmanager.identity.domain.IdentityValidationException;

public final class LoginService implements LoginUseCase {

    private final CredentialsRepository repository;
    private final PasswordVerifier passwordVerifier;

    public LoginService(CredentialsRepository repository, PasswordVerifier passwordVerifier) {
        this.repository = repository;
        this.passwordVerifier = passwordVerifier;
    }

    @Override
    public String authenticate(String rawEmail, char[] rawPassword) {
        try {
            final String normalizedEmail;
            try {
                normalizedEmail = new EmailAddress(rawEmail).value();
            } catch (IdentityValidationException exception) {
                throw new InvalidCredentialsException();
            }
            if (rawPassword == null || rawPassword.length == 0) {
                throw new InvalidCredentialsException();
            }

            var credentials = repository.findActiveByNormalizedEmail(normalizedEmail)
                    .orElseThrow(InvalidCredentialsException::new);
            if (!credentials.emailConfirmed()
                    || !passwordVerifier.matches(rawPassword, credentials.passwordHash())) {
                throw new InvalidCredentialsException();
            }
            return credentials.normalizedEmail();
        } finally {
            if (rawPassword != null) {
                Arrays.fill(rawPassword, '\0');
            }
        }
    }
}
