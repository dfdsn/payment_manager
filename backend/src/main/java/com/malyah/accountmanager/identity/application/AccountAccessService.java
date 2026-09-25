package com.malyah.accountmanager.identity.application;

import java.time.Clock;
import java.util.Optional;

import com.malyah.accountmanager.identity.application.port.AccessTokenCodec;
import com.malyah.accountmanager.identity.application.port.AccountAccessRepository;
import com.malyah.accountmanager.identity.application.port.IdentifierGenerator;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;
import com.malyah.accountmanager.identity.domain.EmailAddress;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

public final class AccountAccessService {

    private final AccountAccessRepository repository;
    private final AccessTokenCodec tokenCodec;
    private final IdentifierGenerator identifierGenerator;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public AccountAccessService(
            AccountAccessRepository repository,
            AccessTokenCodec tokenCodec,
            IdentifierGenerator identifierGenerator,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            Clock clock) {
        this.repository = repository;
        this.tokenCodec = tokenCodec;
        this.identifierGenerator = identifierGenerator;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    public Optional<PendingAccessEmail> prepareEmailConfirmation(String rawEmail) {
        return prepare(rawEmail, AccessTokenPurpose.CONFIRM_EMAIL, true);
    }

    public Optional<PendingAccessEmail> preparePasswordReset(String rawEmail) {
        return prepare(rawEmail, AccessTokenPurpose.RESET_PASSWORD, false);
    }

    public void confirmEmail(String rawToken) {
        var now = clock.instant();
        var token = usableToken(rawToken, AccessTokenPurpose.CONFIRM_EMAIL, now);
        repository.consumeAndConfirmEmail(token.id(), token.userId(), now);
    }

    public void resetPassword(String rawToken, String newPassword) {
        var password = newPassword == null ? null : newPassword.toCharArray();
        passwordPolicy.validate(password);
        var now = clock.instant();
        var token = usableToken(rawToken, AccessTokenPurpose.RESET_PASSWORD, now);
        repository.consumeAndResetPassword(
                token.id(), token.userId(), token.normalizedEmail(), passwordHasher.hash(password), now);
    }

    private Optional<PendingAccessEmail> prepare(
            String rawEmail, AccessTokenPurpose purpose, boolean onlyWhenUnconfirmed) {
        var email = new EmailAddress(rawEmail);
        var account = repository.findByNormalizedEmail(email.value());
        if (account.isEmpty() || (onlyWhenUnconfirmed && account.get().emailConfirmed())) {
            return Optional.empty();
        }

        var now = clock.instant();
        var rawToken = tokenCodec.generate();
        var user = account.get();
        repository.revokeActiveTokens(user.id(), purpose, now);
        repository.store(new AccessTokenRegistration(
                identifierGenerator.next(),
                user.id(),
                purpose,
                tokenCodec.hash(rawToken),
                now.plus(purpose.validity()),
                now));
        return Optional.of(new PendingAccessEmail(user.normalizedEmail(), rawToken, purpose));
    }

    private StoredAccessToken usableToken(String rawToken, AccessTokenPurpose purpose, java.time.Instant now) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            throw new InvalidOrExpiredAccessTokenException();
        }
        return repository.findTokenForUpdate(tokenCodec.hash(rawToken), purpose)
                .filter(token -> token.isUsableAt(now))
                .orElseThrow(InvalidOrExpiredAccessTokenException::new);
    }
}
