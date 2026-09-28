package com.malyah.accountmanager.identity.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public final class JdbcFinancialMemberAccess implements FinancialMemberAccess {
    private final JdbcTemplate jdbc;
    public JdbcFinancialMemberAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireActiveParticipants(UUID spaceId, UUID actorId, UUID payerId) {
        jdbc.queryForObject("select id from family_spaces where id = ? for update", UUID.class, spaceId);
        var members = jdbc.queryForList("""
                select user_id from space_memberships where space_id = ? and active = true
                and role in ('ADMINISTRATOR', 'GUEST')
                """, UUID.class, spaceId);
        if (!members.contains(actorId) || (payerId != null && !members.contains(payerId)))
            throw new AuthenticatedUserContextNotFoundException();
    }
}
