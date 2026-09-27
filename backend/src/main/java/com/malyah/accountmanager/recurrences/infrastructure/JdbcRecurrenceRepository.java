package com.malyah.accountmanager.recurrences.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.recurrences.application.RecurrenceIdempotencyConflictException;
import com.malyah.accountmanager.recurrences.application.StoredRecurrence;
import com.malyah.accountmanager.recurrences.application.StoredRecurrenceCreation;
import com.malyah.accountmanager.recurrences.application.port.RecurrenceRepository;
import com.malyah.accountmanager.recurrences.domain.RecurrenceDefinition;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;

final class JdbcRecurrenceRepository implements RecurrenceRepository {
    private final JdbcTemplate jdbc;
    JdbcRecurrenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public StoredRecurrenceCreation createIdempotently(RecurrenceDefinition d, UUID actorId,
            UUID key, String requestHash, Instant at) {
        int claimed = jdbc.update("""
                insert into recurrence_idempotency_requests(space_id,actor_user_id,idempotency_key,request_hash,created_at)
                values (?,?,?,?,?) on conflict do nothing
                """, d.spaceId(), actorId, key, requestHash, Timestamp.from(at));
        if (claimed == 0) {
            var existing = jdbc.queryForObject("""
                    select request_hash, recurrence_id from recurrence_idempotency_requests
                     where space_id=? and actor_user_id=? and idempotency_key=? for update
                    """, (rs,row) -> new Request(rs.getString(1), rs.getObject(2, UUID.class)), d.spaceId(), actorId, key);
            if (existing == null || !requestHash.equals(existing.hash()) || existing.id() == null)
                throw new RecurrenceIdempotencyConflictException();
            return new StoredRecurrenceCreation(find(d.spaceId(), existing.id()), true);
        }
        jdbc.update("""
                insert into recurrence_definitions(id,space_id,description,amount,value_type,frequency,first_due_date,
                    base_day,last_due_date,category_id,responsible_user_id,created_by_user_id,created_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, d.id(), d.spaceId(), d.description(), d.amount(), d.valueType().name(), d.frequency().name(),
                d.firstDueDate(), d.firstDueDate().getDayOfMonth(), d.lastDueDate(), d.categoryId(),
                d.responsibleUserId(), actorId, Timestamp.from(at));
        jdbc.update("""
                insert into recurrence_audit_events(id,recurrence_id,space_id,actor_user_id,event_type,occurred_at)
                values (?,?,?,?, 'RECURRENCE_CREATED', ?)
                """, UUID.randomUUID(), d.id(), d.spaceId(), actorId, Timestamp.from(at));
        jdbc.update("""
                update recurrence_idempotency_requests set recurrence_id=?,completed_at=?
                 where space_id=? and actor_user_id=? and idempotency_key=?
                """, d.id(), Timestamp.from(at), d.spaceId(), actorId, key);
        return new StoredRecurrenceCreation(find(d.spaceId(), d.id()), false);
    }

    @Override public List<StoredRecurrence> findAll(UUID spaceId) {
        return jdbc.query(selectBase()+" where r.space_id=? order by r.created_at,r.id", this::map, spaceId);
    }

    private StoredRecurrence find(UUID spaceId, UUID id) {
        return jdbc.queryForObject(selectBase()+" where r.space_id=? and r.id=?", this::map, spaceId, id);
    }

    private String selectBase() { return """
            select r.id,r.space_id,r.description,r.amount,r.value_type,r.frequency,r.first_due_date,r.last_due_date,
                   r.category_id,c.name,r.responsible_user_id,responsible.display_name,r.created_by_user_id,
                   creator.display_name,r.created_at,r.version
              from recurrence_definitions r
              left join expense_categories c on c.id=r.category_id
              left join identity_users responsible on responsible.id=r.responsible_user_id
              join identity_users creator on creator.id=r.created_by_user_id
            """; }

    private StoredRecurrence map(ResultSet rs, int row) throws SQLException {
        var definition = new RecurrenceDefinition(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getBigDecimal(4),RecurrenceValueType.valueOf(rs.getString(5)),RecurrenceFrequency.valueOf(rs.getString(6)),
                rs.getObject(7,java.time.LocalDate.class),rs.getObject(8,java.time.LocalDate.class),rs.getObject(9,UUID.class),
                rs.getObject(11,UUID.class),rs.getObject(13,UUID.class),rs.getTimestamp(15).toInstant(),rs.getLong(16));
        return new StoredRecurrence(definition,rs.getString(10),rs.getString(12),rs.getString(14));
    }
    private record Request(String hash, UUID id) { }
}
