package com.malyah.accountmanager.identity.infrastructure;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.InitialSetupRegistration;
import com.malyah.accountmanager.identity.application.port.InitialSetupRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

final class JdbcInitialSetupRepository implements InitialSetupRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcInitialSetupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isCompleted() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select completed_at is not null from installation_state where singleton_id = 1",
                Boolean.class));
    }

    @Override
    public boolean lockAndCheckCompleted() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select completed_at is not null from installation_state where singleton_id = 1 for update",
                Boolean.class));
    }

    @Override
    public void create(InitialSetupRegistration registration) {
        var createdAt = Timestamp.from(registration.createdAt());
        jdbcTemplate.update("""
                insert into identity_users(
                    id, display_name, normalized_email, password_hash, email_confirmed, created_at
                ) values (?, ?, ?, ?, false, ?)
                """,
                registration.administratorId(),
                registration.administratorName(),
                registration.normalizedEmail(),
                registration.passwordHash(),
                createdAt);
        jdbcTemplate.update("""
                insert into family_spaces(id, name, currency_code, locale, time_zone, created_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                registration.spaceId(),
                registration.spaceName(),
                registration.currency(),
                registration.locale(),
                registration.timeZone(),
                createdAt);
        jdbcTemplate.update("""
                insert into space_memberships(id, user_id, space_id, role, active, created_at)
                values (?, ?, ?, ?, true, ?)
                """,
                registration.membershipId(),
                registration.administratorId(),
                registration.spaceId(),
                SpaceRole.ADMINISTRATOR.name(),
                createdAt);
    }

    @Override
    public void markCompleted(InitialSetupRegistration registration) {
        var updated = jdbcTemplate.update("""
                update installation_state
                   set completed_at = ?, administrator_user_id = ?, space_id = ?
                 where singleton_id = 1 and completed_at is null
                """,
                Timestamp.from(registration.createdAt()),
                registration.administratorId(),
                registration.spaceId());
        if (updated != 1) {
            throw new IllegalStateException("A marcação persistente do setup não foi atualizada.");
        }
    }
}
