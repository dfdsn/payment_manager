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
import com.malyah.accountmanager.recurrences.application.AnticipationClaim;
import com.malyah.accountmanager.recurrences.application.StoredOccurrence;
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
            return new StoredRecurrenceCreation(findById(d.spaceId(), existing.id()), true);
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
        return new StoredRecurrenceCreation(findById(d.spaceId(), d.id()), false);
    }

    @Override public List<StoredRecurrence> findAll(UUID spaceId) {
        return jdbc.query(selectBase()+" where r.space_id=? order by r.created_at,r.id", this::map, spaceId);
    }

    @Override public StoredRecurrence findById(UUID spaceId, UUID id) {
        return jdbc.queryForObject(selectBase()+" where r.space_id=? and r.id=?", this::map, spaceId, id);
    }

    @Override public List<StoredOccurrence> findOccurrences(UUID spaceId, java.time.LocalDate from,
            java.time.LocalDate to) {
        return jdbc.query("""
                select o.recurrence_id,o.scheduled_due_date,o.expense_id,e.due_date,e.status,
                       e.charge_amount,e.charge_confirmed
                  from recurrence_occurrences o
                  join expense_entries e on e.id=o.expense_id and e.space_id=o.space_id
                 where o.space_id=? and o.scheduled_due_date between ? and ?
                 order by o.scheduled_due_date,o.recurrence_id
                """, (rs,row) -> new StoredOccurrence(rs.getObject(1,UUID.class),
                        rs.getObject(2,java.time.LocalDate.class),rs.getObject(3,UUID.class),
                        rs.getObject(4,java.time.LocalDate.class),rs.getString(5),rs.getBigDecimal(6),rs.getBoolean(7)),
                spaceId,from,to);
    }

    @Override public List<StoredOccurrence> findConfirmedCharges(UUID spaceId) {
        return jdbc.query("""
                select o.recurrence_id,o.scheduled_due_date,o.expense_id,e.due_date,e.status,
                       e.charge_amount,e.charge_confirmed
                  from recurrence_occurrences o
                  join expense_entries e on e.id=o.expense_id and e.space_id=o.space_id
                 where o.space_id=? and e.charge_confirmed=true
                 order by o.recurrence_id,o.scheduled_due_date
                """, (rs,row) -> new StoredOccurrence(rs.getObject(1,UUID.class),
                        rs.getObject(2,java.time.LocalDate.class),rs.getObject(3,UUID.class),
                        rs.getObject(4,java.time.LocalDate.class),rs.getString(5),rs.getBigDecimal(6),rs.getBoolean(7)),
                spaceId);
    }

    @Override public void lockForChargeConfirmation(UUID spaceId, UUID recurrenceId) {
        jdbc.query("select id from recurrence_definitions where id=? and space_id=? for no key update",
                (rs,row)->rs.getObject(1,UUID.class),recurrenceId,spaceId);
    }

    @Override public AnticipationClaim claimAnticipation(UUID spaceId, UUID actorId, UUID recurrenceId,
            java.time.LocalDate dueDate, UUID key, String requestHash, Instant at) {
        var claimed=jdbc.update("""
                insert into recurrence_anticipation_requests(space_id,actor_user_id,idempotency_key,request_hash,
                    recurrence_id,scheduled_due_date,created_at)
                values(?,?,?,?,?,?,?) on conflict do nothing
                """,spaceId,actorId,key,requestHash,recurrenceId,dueDate,Timestamp.from(at));
        if(claimed==1) return new AnticipationClaim(false,null);
        var existing=jdbc.queryForObject("""
                select request_hash,expense_id from recurrence_anticipation_requests
                 where space_id=? and actor_user_id=? and idempotency_key=? for update
                """,(rs,row)->new Request(rs.getString(1),rs.getObject(2,UUID.class)),spaceId,actorId,key);
        if(existing==null||!requestHash.equals(existing.hash())||existing.id()==null)
            throw new RecurrenceIdempotencyConflictException();
        return new AnticipationClaim(true,existing.id());
    }

    @Override public void completeAnticipation(UUID spaceId,UUID actorId,UUID key,UUID expenseId,Instant at) {
        jdbc.update("""
                update recurrence_anticipation_requests set expense_id=?,completed_at=?
                 where space_id=? and actor_user_id=? and idempotency_key=?
                """,expenseId,Timestamp.from(at),spaceId,actorId,key);
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
