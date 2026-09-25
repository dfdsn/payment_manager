package com.malyah.accountmanager.identity.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.ManagedMember;
import com.malyah.accountmanager.identity.application.MembershipActor;
import com.malyah.accountmanager.identity.application.MembershipConflictException;
import com.malyah.accountmanager.identity.application.MembershipLifecycleEvent;
import com.malyah.accountmanager.identity.application.port.MembershipRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;

final class JdbcMembershipRepository implements MembershipRepository {

    private final JdbcTemplate jdbc;

    JdbcMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MembershipActor> findActiveActor(String normalizedEmail) {
        return jdbc.query("""
                select u.id, m.space_id, u.normalized_email, m.role
                  from identity_users u
                  join space_memberships m on m.user_id = u.id and m.active = true
                 where u.normalized_email = ?
                """, (rs, row) -> actor(rs), normalizedEmail).stream().findFirst();
    }

    @Override
    public void lockSpace(UUID spaceId) {
        jdbc.queryForObject("select id from family_spaces where id = ? for update", UUID.class, spaceId);
    }

    @Override
    public List<ManagedMember> findActiveMembers(UUID spaceId, UUID currentUserId) {
        return jdbc.query("""
                select u.id, u.display_name, u.normalized_email, m.role
                  from space_memberships m
                  join identity_users u on u.id = m.user_id
                 where m.space_id = ? and m.active = true
                 order by case m.role when 'ADMINISTRATOR' then 0 else 1 end, u.display_name
                """, (rs, row) -> new ManagedMember(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        SpaceRole.valueOf(rs.getString(4)), currentUserId.equals(rs.getObject(1, UUID.class))),
                spaceId);
    }

    @Override
    public Optional<MembershipActor> findActiveMember(UUID spaceId, UUID userId) {
        return jdbc.query("""
                select u.id, m.space_id, u.normalized_email, m.role
                  from space_memberships m
                  join identity_users u on u.id = m.user_id
                 where m.space_id = ? and m.user_id = ? and m.active = true
                """, (rs, row) -> actor(rs), spaceId, userId).stream().findFirst();
    }

    @Override
    public void endMembership(UUID spaceId, UUID userId, UUID endedByUserId, String reason, java.time.Instant endedAt) {
        var changed = jdbc.update("""
                update space_memberships
                   set active = false, ended_at = ?, ended_by_user_id = ?, end_reason = ?
                 where space_id = ? and user_id = ? and active = true
                """, Timestamp.from(endedAt), endedByUserId, reason, spaceId, userId);
        if (changed != 1) {
            throw new MembershipConflictException("A associação já foi encerrada ou foi alterada por outra operação.");
        }
    }

    @Override
    public void transferAdministration(UUID spaceId, UUID currentAdministratorId, UUID newAdministratorId) {
        var formerAdministratorChanged = jdbc.update("""
                update space_memberships set role = 'GUEST'
                 where space_id = ? and user_id = ? and active = true and role = 'ADMINISTRATOR'
                """, spaceId, currentAdministratorId);
        var newAdministratorChanged = jdbc.update("""
                update space_memberships set role = 'ADMINISTRATOR'
                 where space_id = ? and user_id = ? and active = true and role = 'GUEST'
                """, spaceId, newAdministratorId);
        if (formerAdministratorChanged != 1 || newAdministratorChanged != 1) {
            throw new MembershipConflictException("Os papéis mudaram durante a transferência. Atualize e tente novamente.");
        }
    }

    @Override
    public void append(MembershipLifecycleEvent event) {
        jdbc.update("""
                insert into membership_lifecycle_events(
                    id, space_id, actor_user_id, subject_user_id, event_type,
                    actor_previous_role, actor_new_role, subject_previous_role, subject_new_role, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.spaceId(), event.actorUserId(), event.subjectUserId(), event.type().name(),
                event.actorPreviousRole().name(), role(event.actorNewRole()),
                event.subjectPreviousRole().name(), role(event.subjectNewRole()), Timestamp.from(event.occurredAt()));
    }

    private MembershipActor actor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MembershipActor(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), SpaceRole.valueOf(rs.getString(4)));
    }

    private String role(SpaceRole role) {
        return role == null ? null : role.name();
    }
}
