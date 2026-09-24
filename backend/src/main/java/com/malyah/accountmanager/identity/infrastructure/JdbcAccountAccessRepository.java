package com.malyah.accountmanager.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.AccessTokenRegistration;
import com.malyah.accountmanager.identity.application.LoginCredentials;
import com.malyah.accountmanager.identity.application.StoredAccessToken;
import com.malyah.accountmanager.identity.application.UserAccessAccount;
import com.malyah.accountmanager.identity.application.port.AccountAccessRepository;
import com.malyah.accountmanager.identity.application.port.CredentialsRepository;
import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

final class JdbcAccountAccessRepository implements AccountAccessRepository, CredentialsRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcAccountAccessRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserAccessAccount> findByNormalizedEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                select id, normalized_email, email_confirmed
                  from identity_users
                 where normalized_email = ?
                 for update
                """, (resultSet, row) -> new UserAccessAccount(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("normalized_email"),
                        resultSet.getBoolean("email_confirmed")), normalizedEmail)
                .stream().findFirst();
    }

    @Override
    public Optional<LoginCredentials> findActiveByNormalizedEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                select u.normalized_email, u.password_hash, u.email_confirmed
                  from identity_users u
                 where u.normalized_email = ?
                   and exists (
                       select 1 from space_memberships m
                        where m.user_id = u.id and m.active
                   )
                """, (resultSet, row) -> new LoginCredentials(
                        resultSet.getString("normalized_email"),
                        resultSet.getString("password_hash"),
                        resultSet.getBoolean("email_confirmed")), normalizedEmail)
                .stream().findFirst();
    }

    @Override
    public void revokeActiveTokens(UUID userId, AccessTokenPurpose purpose, java.time.Instant revokedAt) {
        jdbcTemplate.update("""
                update identity_access_tokens
                   set revoked_at = ?
                 where user_id = ? and purpose = ?
                   and consumed_at is null and revoked_at is null
                """, Timestamp.from(revokedAt), userId, purpose.name());
    }

    @Override
    public void store(AccessTokenRegistration registration) {
        jdbcTemplate.update("""
                insert into identity_access_tokens(
                    id, user_id, purpose, token_hash, expires_at, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                registration.id(), registration.userId(), registration.purpose().name(),
                registration.tokenHash(), Timestamp.from(registration.expiresAt()),
                Timestamp.from(registration.createdAt()));
    }

    @Override
    public Optional<StoredAccessToken> findTokenForUpdate(String tokenHash, AccessTokenPurpose purpose) {
        return jdbcTemplate.query("""
                select t.id, t.user_id, u.normalized_email, t.purpose,
                       t.expires_at, t.consumed_at, t.revoked_at
                  from identity_access_tokens t
                  join identity_users u on u.id = t.user_id
                 where t.token_hash = ? and t.purpose = ?
                 for update of t
                """, (resultSet, row) -> mapToken(resultSet), tokenHash, purpose.name())
                .stream().findFirst();
    }

    @Override
    public void consumeAndConfirmEmail(UUID tokenId, UUID userId, java.time.Instant consumedAt) {
        consume(tokenId, consumedAt);
        if (jdbcTemplate.update("update identity_users set email_confirmed = true where id = ?", userId) != 1) {
            throw new IllegalStateException("Usuário do token de confirmação não foi atualizado.");
        }
    }

    @Override
    public void consumeAndResetPassword(
            UUID tokenId,
            UUID userId,
            String normalizedEmail,
            String passwordHash,
            java.time.Instant consumedAt) {
        consume(tokenId, consumedAt);
        if (jdbcTemplate.update("update identity_users set password_hash = ? where id = ?", passwordHash, userId) != 1) {
            throw new IllegalStateException("Usuário do token de recuperação não foi atualizado.");
        }
        jdbcTemplate.update("delete from spring_session where principal_name = ?", normalizedEmail);
    }

    private void consume(UUID tokenId, java.time.Instant consumedAt) {
        var updated = jdbcTemplate.update("""
                update identity_access_tokens
                   set consumed_at = ?
                 where id = ? and consumed_at is null and revoked_at is null
                """, Timestamp.from(consumedAt), tokenId);
        if (updated != 1) {
            throw new IllegalStateException("O token não pôde ser consumido uma única vez.");
        }
    }

    private StoredAccessToken mapToken(ResultSet resultSet) throws SQLException {
        return new StoredAccessToken(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("normalized_email"),
                AccessTokenPurpose.valueOf(resultSet.getString("purpose")),
                resultSet.getTimestamp("expires_at").toInstant(),
                instantOrNull(resultSet.getTimestamp("consumed_at")),
                instantOrNull(resultSet.getTimestamp("revoked_at")));
    }

    private java.time.Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
