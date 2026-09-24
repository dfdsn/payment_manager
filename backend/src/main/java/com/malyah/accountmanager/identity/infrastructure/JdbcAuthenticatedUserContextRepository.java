package com.malyah.accountmanager.identity.infrastructure;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

final class JdbcAuthenticatedUserContextRepository implements AuthenticatedUserContextRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcAuthenticatedUserContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AuthenticatedUserContext> findActiveByEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                select u.id, u.display_name, u.normalized_email,
                       s.id, s.name, m.role, s.currency_code, s.locale, s.time_zone
                  from identity_users u
                  join space_memberships m on m.user_id = u.id and m.active = true
                  join family_spaces s on s.id = m.space_id
                 where u.normalized_email = ?
                """,
                (resultSet, rowNumber) -> new AuthenticatedUserContext(
                        resultSet.getObject(1, java.util.UUID.class),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getObject(4, java.util.UUID.class),
                        resultSet.getString(5),
                        SpaceRole.valueOf(resultSet.getString(6)),
                        resultSet.getString(7),
                        resultSet.getString(8),
                        resultSet.getString(9)),
                normalizedEmail).stream().findFirst();
    }
}
