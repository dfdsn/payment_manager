package com.malyah.accountmanager.identity.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.identity.application.AccessTokenRegistration;
import com.malyah.accountmanager.identity.application.StoredAccessToken;
import com.malyah.accountmanager.identity.application.UserAccessAccount;
import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

public interface AccountAccessRepository {

    Optional<UserAccessAccount> findByNormalizedEmail(String normalizedEmail);

    void revokeActiveTokens(UUID userId, AccessTokenPurpose purpose, Instant revokedAt);

    void store(AccessTokenRegistration registration);

    Optional<StoredAccessToken> findTokenForUpdate(String tokenHash, AccessTokenPurpose purpose);

    void consumeAndConfirmEmail(UUID tokenId, UUID userId, Instant consumedAt);

    void consumeAndResetPassword(
            UUID tokenId, UUID userId, String normalizedEmail, String passwordHash, Instant consumedAt);
}
