package com.malyah.accountmanager.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.port.AccessTokenCodec;
import com.malyah.accountmanager.identity.application.port.AccountAccessRepository;
import com.malyah.accountmanager.identity.application.port.PasswordHasher;
import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;
import com.malyah.accountmanager.identity.domain.PasswordPolicy;

class AccountAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private final AccountAccessRepository repository = mock(AccountAccessRepository.class);
    private final AccessTokenCodec codec = mock(AccessTokenCodec.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private AccountAccessService service;

    @BeforeEach
    void setUp() {
        service = new AccountAccessService(
                repository,
                codec,
                () -> TOKEN_ID,
                hasher,
                new PasswordPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesConfirmationForUnconfirmedAccountAndRevokesPreviousToken() {
        given(repository.findByNormalizedEmail("admin@example.com"))
                .willReturn(Optional.of(new UserAccessAccount(USER_ID, "admin@example.com", false)));
        given(codec.generate()).willReturn("raw-token");
        given(codec.hash("raw-token")).willReturn("hash");

        var pending = service.prepareEmailConfirmation(" ADMIN@EXAMPLE.COM ").orElseThrow();

        assertThat(pending.recipient()).isEqualTo("admin@example.com");
        assertThat(pending.purpose()).isEqualTo(AccessTokenPurpose.CONFIRM_EMAIL);
        then(repository).should().revokeActiveTokens(USER_ID, AccessTokenPurpose.CONFIRM_EMAIL, NOW);
        then(repository).should().store(new AccessTokenRegistration(
                TOKEN_ID, USER_ID, AccessTokenPurpose.CONFIRM_EMAIL, "hash", NOW.plusSeconds(86400), NOW));
    }

    @Test
    void keepsRequestGenericForUnknownOrAlreadyConfirmedAccount() {
        given(repository.findByNormalizedEmail("unknown@example.com")).willReturn(Optional.empty());
        given(repository.findByNormalizedEmail("admin@example.com"))
                .willReturn(Optional.of(new UserAccessAccount(USER_ID, "admin@example.com", true)));

        assertThat(service.preparePasswordReset("unknown@example.com")).isEmpty();
        assertThat(service.prepareEmailConfirmation("admin@example.com")).isEmpty();

        then(codec).shouldHaveNoInteractions();
    }

    @Test
    void confirmsOnlyUsableSinglePurposeToken() {
        given(codec.hash("valid")).willReturn("hash-valid");
        given(repository.findTokenForUpdate("hash-valid", AccessTokenPurpose.CONFIRM_EMAIL))
                .willReturn(Optional.of(token(AccessTokenPurpose.CONFIRM_EMAIL, NOW.plusSeconds(60), null, null)));

        service.confirmEmail("valid");

        then(repository).should().consumeAndConfirmEmail(TOKEN_ID, USER_ID, NOW);
    }

    @Test
    void rejectsExpiredConsumedRevokedAndMalformedTokens() {
        given(codec.hash("expired")).willReturn("hash-expired");
        given(repository.findTokenForUpdate("hash-expired", AccessTokenPurpose.CONFIRM_EMAIL))
                .willReturn(Optional.of(token(AccessTokenPurpose.CONFIRM_EMAIL, NOW, null, null)));
        given(codec.hash("consumed")).willReturn("hash-consumed");
        given(repository.findTokenForUpdate("hash-consumed", AccessTokenPurpose.CONFIRM_EMAIL))
                .willReturn(Optional.of(token(AccessTokenPurpose.CONFIRM_EMAIL, NOW.plusSeconds(60), NOW, null)));
        given(codec.hash("revoked")).willReturn("hash-revoked");
        given(repository.findTokenForUpdate("hash-revoked", AccessTokenPurpose.CONFIRM_EMAIL))
                .willReturn(Optional.of(token(AccessTokenPurpose.CONFIRM_EMAIL, NOW.plusSeconds(60), null, NOW)));

        assertThatThrownBy(() -> service.confirmEmail("expired"))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
        assertThatThrownBy(() -> service.confirmEmail("consumed"))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
        assertThatThrownBy(() -> service.confirmEmail("revoked"))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
        assertThatThrownBy(() -> service.confirmEmail(" "))
                .isInstanceOf(InvalidOrExpiredAccessTokenException.class);
    }

    @Test
    void resetsPasswordAndDelegatesSessionRevocationAtomicallyToRepository() {
        given(codec.hash("reset")).willReturn("hash-reset");
        given(repository.findTokenForUpdate("hash-reset", AccessTokenPurpose.RESET_PASSWORD))
                .willReturn(Optional.of(token(AccessTokenPurpose.RESET_PASSWORD, NOW.plusSeconds(60), null, null)));
        given(hasher.hash(org.mockito.ArgumentMatchers.any(char[].class))).willReturn("{bcrypt}new-hash");

        service.resetPassword("reset", "nova senha segura 2026");

        then(repository).should().consumeAndResetPassword(
                TOKEN_ID, USER_ID, "admin@example.com", "{bcrypt}new-hash", NOW);
    }

    private StoredAccessToken token(
            AccessTokenPurpose purpose, Instant expiresAt, Instant consumedAt, Instant revokedAt) {
        return new StoredAccessToken(
                TOKEN_ID, USER_ID, "admin@example.com", purpose, expiresAt, consumedAt, revokedAt);
    }
}
