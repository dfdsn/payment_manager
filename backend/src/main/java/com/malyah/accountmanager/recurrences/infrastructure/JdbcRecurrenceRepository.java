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
import com.malyah.accountmanager.recurrences.application.ChangeClaim;
import com.malyah.accountmanager.recurrences.application.RecurrenceChangeRecord;
import com.malyah.accountmanager.recurrences.application.RecurrenceChangeView;
import com.malyah.accountmanager.recurrences.application.RecurrenceNotFoundException;
import com.malyah.accountmanager.recurrences.application.RecurrenceSegmentView;
import com.malyah.accountmanager.recurrences.application.RecurrenceVersionConflictException;
import com.malyah.accountmanager.recurrences.application.ScheduleLock;
import com.malyah.accountmanager.recurrences.application.StoredSchedule;
import com.malyah.accountmanager.recurrences.domain.RecurrenceConfiguration;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSegment;
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
                insert into recurrence_segments(id,recurrence_id,space_id,effective_month,base_day,frequency,description,
                    amount,estimate_reset,category_id,responsible_user_id,change_id,created_at)
                values (?,?,?,?,?,?,?,?,true,?,?,null,?)
                """, UUID.randomUUID(), d.id(), d.spaceId(), d.firstDueDate().withDayOfMonth(1),
                d.firstDueDate().getDayOfMonth(), d.frequency().name(), d.description(), d.amount(), d.categoryId(),
                d.responsibleUserId(), Timestamp.from(at));
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
                       e.charge_amount,e.charge_confirmed,o.review_reason
                  from recurrence_occurrences o
                  join expense_entries e on e.id=o.expense_id and e.space_id=o.space_id
                 where o.space_id=? and o.scheduled_due_date between ? and ?
                 order by o.scheduled_due_date,o.recurrence_id
                """, (rs,row) -> new StoredOccurrence(rs.getObject(1,UUID.class),
                        rs.getObject(2,java.time.LocalDate.class),rs.getObject(3,UUID.class),
                        rs.getObject(4,java.time.LocalDate.class),rs.getString(5),rs.getBigDecimal(6),rs.getBoolean(7),
                        rs.getString(8)),
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

    @Override public StoredSchedule loadSchedule(UUID spaceId, UUID id, ScheduleLock lock) {
        var suffix=switch(lock){ case NONE->""; case SHARE->" for share of r"; case UPDATE->" for no key update of r"; };
        // Locks the definition row alone; the joined names are read without locks.
        if(lock!=ScheduleLock.NONE) jdbc.query("select r.id from recurrence_definitions r where r.space_id=? and r.id=?"+suffix,
                (rs,row)->rs.getObject(1,UUID.class),spaceId,id);
        var stored=jdbc.query(selectBase()+" where r.space_id=? and r.id=?",this::map,spaceId,id).stream().findFirst()
                .orElseThrow(RecurrenceNotFoundException::new);
        var closure=closure(spaceId,id);
        return schedule(stored,segments(spaceId,id),closure);
    }

    @Override public List<StoredSchedule> findSchedules(UUID spaceId) {
        var segments=segments(spaceId,null).stream().collect(java.util.stream.Collectors.groupingBy(Segment::recurrenceId));
        var closures=jdbc.query("""
                select r.id,r.closed_at,r.closure_reason,u.display_name from recurrence_definitions r
                  left join identity_users u on u.id=r.closed_by_user_id where r.space_id=?
                """,(rs,row)->new Closure(rs.getObject(1,UUID.class),instant(rs,2),rs.getString(3),rs.getString(4)),spaceId)
                .stream().collect(java.util.stream.Collectors.toMap(Closure::recurrenceId,c->c));
        return findAll(spaceId).stream().map(stored->schedule(stored,
                segments.getOrDefault(stored.definition().id(),List.of()),closures.get(stored.definition().id()))).toList();
    }

    @Override public ChangeClaim claimChange(UUID spaceId, UUID actorId, UUID key, String requestHash,
            UUID recurrenceId, Instant at) {
        var claimed=jdbc.update("""
                insert into recurrence_change_requests(space_id,actor_user_id,idempotency_key,request_hash,recurrence_id,created_at)
                values(?,?,?,?,?,?) on conflict do nothing
                """,spaceId,actorId,key,requestHash,recurrenceId,Timestamp.from(at));
        if(claimed==1) return new ChangeClaim(false,null);
        var existing=jdbc.query("""
                select request_hash,change_id from recurrence_change_requests
                 where space_id=? and actor_user_id=? and idempotency_key=? for update
                """,(rs,row)->new Request(rs.getString(1),rs.getObject(2,UUID.class)),spaceId,actorId,key)
                .stream().findFirst().orElseThrow(RecurrenceIdempotencyConflictException::new);
        if(!requestHash.equals(existing.hash())||existing.id()==null) throw new RecurrenceIdempotencyConflictException();
        return new ChangeClaim(true,existing.id());
    }

    @Override public void completeChange(UUID spaceId, UUID actorId, UUID key, UUID changeId, Instant at) {
        jdbc.update("""
                update recurrence_change_requests set change_id=?,completed_at=?
                 where space_id=? and actor_user_id=? and idempotency_key=?
                """,changeId,Timestamp.from(at),spaceId,actorId,key);
    }

    @Override public void saveChange(RecurrenceChangeRecord c) {
        var at=Timestamp.from(c.at());
        jdbc.update("""
                insert into recurrence_change_events(id,recurrence_id,space_id,actor_user_id,change_type,occurred_at,
                    from_version,to_version,effective_due_date,changed_fields,previous_configuration,new_configuration,
                    reason,impact_hash,updated_count,removed_count,review_count,preserved_count)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,c.changeId(),c.recurrenceId(),c.spaceId(),c.actorId(),c.type(),at,c.fromVersion(),c.fromVersion()+1,
                c.effectiveDueDate(),String.join(",",c.changedFields()),c.previousConfiguration(),c.newConfiguration(),
                c.reason(),c.impactHash(),c.updatedCount(),c.removedCount(),c.reviewCount(),c.preservedCount());
        for(var segment:c.segments()) {
            var cfg=segment.configuration();
            jdbc.update("""
                    insert into recurrence_segments(id,recurrence_id,space_id,effective_month,base_day,frequency,description,
                        amount,estimate_reset,category_id,responsible_user_id,change_id,created_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    on conflict(recurrence_id,effective_month) do update set base_day=excluded.base_day,
                        frequency=excluded.frequency,description=excluded.description,amount=excluded.amount,
                        estimate_reset=excluded.estimate_reset,category_id=excluded.category_id,
                        responsible_user_id=excluded.responsible_user_id,change_id=excluded.change_id
                     where (recurrence_segments.base_day,recurrence_segments.frequency,recurrence_segments.description,
                            recurrence_segments.amount,recurrence_segments.estimate_reset,recurrence_segments.category_id,
                            recurrence_segments.responsible_user_id)
                           is distinct from (excluded.base_day,excluded.frequency,excluded.description,excluded.amount,
                            excluded.estimate_reset,excluded.category_id,excluded.responsible_user_id)
                    """,UUID.randomUUID(),c.recurrenceId(),c.spaceId(),segment.effectiveMonth().atDay(1),cfg.baseDay(),
                    cfg.frequency().name(),cfg.description(),cfg.amount(),segment.estimateReset(),cfg.categoryId(),
                    cfg.responsibleUserId(),c.changeId(),at);
        }
        var current=c.currentConfiguration();
        var closure="CLOSURE".equals(c.type());
        var updated=jdbc.update("""
                update recurrence_definitions set description=?,amount=?,frequency=?,base_day=?,category_id=?,
                    responsible_user_id=?,last_due_date=?,version=version+1,
                    closed_at=case when ? then ? else closed_at end,
                    closed_by_user_id=case when ? then ? else closed_by_user_id end,
                    closure_reason=case when ? then ? else closure_reason end
                 where id=? and space_id=? and version=?
                """,current.description(),current.amount(),current.frequency().name(),current.baseDay(),
                current.categoryId(),current.responsibleUserId(),c.lastDueDate(),closure,at,closure,c.actorId(),closure,
                c.reason(),c.recurrenceId(),c.spaceId(),c.fromVersion());
        if(updated!=1) throw new RecurrenceVersionConflictException();
        if(!closure) jdbc.update("""
                update recurrence_occurrences set review_reason=null,review_change_id=null
                 where recurrence_id=? and space_id=? and scheduled_month>=? and review_reason='OUTSIDE_SCHEDULE'
                """,c.recurrenceId(),c.spaceId(),c.effectiveDueDate().withDayOfMonth(1));
        for(var review:c.reviews().entrySet()) jdbc.update("""
                update recurrence_occurrences set review_reason=?,review_change_id=?
                 where recurrence_id=? and space_id=? and expense_id=?
                """,review.getValue(),c.changeId(),c.recurrenceId(),c.spaceId(),review.getKey());
    }

    @Override public RecurrenceChangeView findChange(UUID spaceId, UUID changeId) {
        return jdbc.query(selectChanges()+" where c.space_id=? and c.id=?",(rs,row)->change(rs),spaceId,changeId)
                .stream().findFirst().orElseThrow(RecurrenceNotFoundException::new);
    }

    @Override public java.util.Map<UUID,List<RecurrenceChangeView>> findChanges(UUID spaceId) {
        var result=new java.util.LinkedHashMap<UUID,List<RecurrenceChangeView>>();
        jdbc.query(selectChanges()+" where c.space_id=? order by c.recurrence_id,c.to_version",(rs,row)->{
            result.computeIfAbsent(rs.getObject(14,UUID.class),k->new java.util.ArrayList<>()).add(change(rs));
            return null;
        },spaceId);
        return result;
    }

    private String selectChanges() { return """
            select c.id,c.change_type,c.actor_user_id,u.display_name,c.occurred_at,c.to_version,c.effective_due_date,
                   c.changed_fields,c.reason,c.updated_count,c.removed_count,c.review_count,c.preserved_count,c.recurrence_id
              from recurrence_change_events c join identity_users u on u.id=c.actor_user_id
            """; }

    private RecurrenceChangeView change(ResultSet rs) throws SQLException {
        return new RecurrenceChangeView(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getLong(6),rs.getObject(7,java.time.LocalDate.class),
                List.of(rs.getString(8).split(",")),rs.getString(9),rs.getInt(10),rs.getInt(11),rs.getInt(12),rs.getInt(13));
    }

    private List<Segment> segments(UUID spaceId, UUID recurrenceId) {
        return jdbc.query("""
                select s.recurrence_id,s.effective_month,s.description,s.amount,s.frequency,s.base_day,s.category_id,
                       c.name,s.responsible_user_id,u.display_name,s.estimate_reset
                  from recurrence_segments s
                  left join expense_categories c on c.id=s.category_id
                  left join identity_users u on u.id=s.responsible_user_id
                 where s.space_id=? and (?::uuid is null or s.recurrence_id=?)
                 order by s.recurrence_id,s.effective_month
                """,(rs,row)->{
                    var month=java.time.YearMonth.from(rs.getObject(2,java.time.LocalDate.class));
                    var configuration=new RecurrenceConfiguration(rs.getString(3),rs.getBigDecimal(4),
                            RecurrenceFrequency.valueOf(rs.getString(5)),rs.getInt(6),rs.getObject(7,UUID.class),
                            rs.getObject(9,UUID.class));
                    return new Segment(rs.getObject(1,UUID.class),new RecurrenceSegment(month,configuration,rs.getBoolean(11)),
                            new RecurrenceSegmentView(month.atDay(1),configuration.description(),
                                    configuration.amount().setScale(2).toPlainString(),configuration.frequency(),
                                    configuration.baseDay(),configuration.categoryId(),rs.getString(8),
                                    configuration.responsibleUserId(),rs.getString(10)));
                },spaceId,recurrenceId,recurrenceId);
    }

    private Closure closure(UUID spaceId, UUID id) {
        return jdbc.query("""
                select r.id,r.closed_at,r.closure_reason,u.display_name from recurrence_definitions r
                  left join identity_users u on u.id=r.closed_by_user_id where r.space_id=? and r.id=?
                """,(rs,row)->new Closure(rs.getObject(1,UUID.class),instant(rs,2),rs.getString(3),rs.getString(4)),
                spaceId,id).stream().findFirst().orElse(null);
    }

    private static StoredSchedule schedule(StoredRecurrence stored, List<Segment> segments, Closure closure) {
        return new StoredSchedule(stored,segments.stream().map(Segment::segment).toList(),
                segments.stream().map(Segment::view).toList(),closure==null?null:closure.closedAt(),
                closure==null?null:closure.reason(),closure==null?null:closure.closedBy());
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }

    private record Segment(UUID recurrenceId, RecurrenceSegment segment, RecurrenceSegmentView view) { }
    private record Closure(UUID recurrenceId, Instant closedAt, String reason, String closedBy) { }

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
