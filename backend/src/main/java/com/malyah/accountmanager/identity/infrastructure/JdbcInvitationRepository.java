package com.malyah.accountmanager.identity.infrastructure;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.InvitationAccount;
import com.malyah.accountmanager.identity.application.InvitationActor;
import com.malyah.accountmanager.identity.application.InvitationRegistration;
import com.malyah.accountmanager.identity.application.StoredInvitation;
import com.malyah.accountmanager.identity.application.port.InvitationRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

final class JdbcInvitationRepository implements InvitationRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcInvitationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<InvitationActor> findActorByEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                select u.id, m.space_id, m.role
                  from identity_users u
                  join space_memberships m on m.user_id = u.id and m.active = true
                 where u.normalized_email = ?
                """, (rs, row) -> new InvitationActor(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        SpaceRole.valueOf(rs.getString(3))), normalizedEmail).stream().findFirst();
    }

    @Override
    public void lockSpace(UUID spaceId) {
        jdbcTemplate.queryForObject("select id from family_spaces where id = ? for update", UUID.class, spaceId);
    }

    @Override
    public int countActiveMembers(UUID spaceId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from space_memberships where space_id = ? and active = true",
                Integer.class,
                spaceId);
    }

    @Override
    public Optional<StoredInvitation> findActiveBySpaceForUpdate(UUID spaceId) {
        return invitations("""
                select i.id, i.space_id, s.name, i.invited_email,
                       i.expires_at, i.consumed_at, i.revoked_at, i.created_at
                  from space_invitations i
                  join family_spaces s on s.id = i.space_id
                 where i.space_id = ? and i.consumed_at is null and i.revoked_at is null
                 for update of i
                """, spaceId);
    }

    @Override
    public Optional<StoredInvitation> findByTokenHash(String tokenHash) {
        return invitations("""
                select i.id, i.space_id, s.name, i.invited_email,
                       i.expires_at, i.consumed_at, i.revoked_at, i.created_at
                  from space_invitations i
                  join family_spaces s on s.id = i.space_id
                 where i.token_hash = ?
                """, tokenHash);
    }

    @Override
    public Optional<StoredInvitation> findByTokenHashForUpdate(String tokenHash) {
        return invitations("""
                select i.id, i.space_id, s.name, i.invited_email,
                       i.expires_at, i.consumed_at, i.revoked_at, i.created_at
                  from space_invitations i
                  join family_spaces s on s.id = i.space_id
                 where i.token_hash = ?
                 for update of i
                """, tokenHash);
    }

    @Override
    public Optional<InvitationAccount> findAccountByEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                select u.id, u.normalized_email, u.email_confirmed,
                       (select m.space_id from space_memberships m
                         where m.user_id = u.id and m.active = true) as active_space_id
                  from identity_users u
                 where u.normalized_email = ?
                """, (rs, row) -> new InvitationAccount(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getBoolean(3),
                        rs.getObject(4, UUID.class)), normalizedEmail).stream().findFirst();
    }

    @Override
    public void store(InvitationRegistration registration) {
        jdbcTemplate.update("""
                insert into space_invitations(
                    id, space_id, invited_email, invited_by_user_id, token_hash, expires_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                registration.id(),
                registration.spaceId(),
                registration.invitedEmail(),
                registration.invitedByUserId(),
                registration.tokenHash(),
                Timestamp.from(registration.expiresAt()),
                Timestamp.from(registration.createdAt()));
    }

    @Override
    public void revoke(UUID invitationId, java.time.Instant revokedAt) {
        var updated = jdbcTemplate.update("""
                update space_invitations set revoked_at = ?
                 where id = ? and consumed_at is null and revoked_at is null
                """, Timestamp.from(revokedAt), invitationId);
        if (updated != 1) {
            throw new IllegalStateException("O convite pendente não pôde ser revogado.");
        }
    }

    @Override
    public void createGuestAccountAndMembership(
            UUID userId,
            UUID membershipId,
            UUID spaceId,
            String displayName,
            String normalizedEmail,
            String passwordHash,
            java.time.Instant createdAt) {
        jdbcTemplate.update("""
                insert into identity_users(
                    id, display_name, normalized_email, password_hash, email_confirmed, created_at
                ) values (?, ?, ?, ?, true, ?)
                """, userId, displayName, normalizedEmail, passwordHash, Timestamp.from(createdAt));
        createGuestMembership(membershipId, userId, spaceId, createdAt);
    }

    @Override
    public void createGuestMembership(UUID membershipId, UUID userId, UUID spaceId, java.time.Instant createdAt) {
        jdbcTemplate.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, 'GUEST', true, ?)
                """, membershipId, userId, spaceId, Timestamp.from(createdAt));
    }

    @Override
    public void confirmEmailAndCreateGuestMembership(
            UUID membershipId, UUID userId, UUID spaceId, java.time.Instant createdAt) {
        var updated = jdbcTemplate.update(
                "update identity_users set email_confirmed = true where id = ? and email_confirmed = false",
                userId);
        if (updated != 1) {
            throw new IllegalStateException("A conta convidada não pôde ser confirmada.");
        }
        createGuestMembership(membershipId, userId, spaceId, createdAt);
    }

    @Override
    public void consume(UUID invitationId, java.time.Instant consumedAt) {
        var updated = jdbcTemplate.update("""
                update space_invitations set consumed_at = ?
                 where id = ? and consumed_at is null and revoked_at is null and expires_at > ?
                """, Timestamp.from(consumedAt), invitationId, Timestamp.from(consumedAt));
        if (updated != 1) {
            throw new IllegalStateException("O convite não pôde ser consumido.");
        }
    }

    private Optional<StoredInvitation> invitations(String sql, Object argument) {
        return jdbcTemplate.query(sql, (rs, row) -> new StoredInvitation(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8).toInstant()), argument).stream().findFirst();
    }
}
