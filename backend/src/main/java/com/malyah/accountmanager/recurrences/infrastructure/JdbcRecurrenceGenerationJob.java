package com.malyah.accountmanager.recurrences.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.RecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.recurrences.application.UpcomingOccurrenceGeneration;
import com.malyah.accountmanager.recurrences.domain.RecurrenceConfiguration;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSchedule;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSegment;

/**
 * Monthly generation (H04.2). Since H04.5 the calendar follows the segments of each recurrence, and every job
 * revalidates the current definition under a share lock before persisting: a period removed by a change or closure
 * is marked SKIPPED, and the values used are those in force for the period at processing time. Since H08.2 the
 * reminders ask for the occurrences due in their window through {@link #materializeUpcoming}, using the same queue.
 */
public final class JdbcRecurrenceGenerationJob implements UpcomingOccurrenceGeneration {
    private static final Logger LOG=LoggerFactory.getLogger(JdbcRecurrenceGenerationJob.class);
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final RecurringExpenseMaterializer materializer;
    private final Clock clock; private final Duration lease; private final int batch;
    public JdbcRecurrenceGenerationJob(JdbcTemplate jdbc,TransactionTemplate transactions,RecurringExpenseMaterializer materializer,
            Clock clock,Duration lease,int batch){this.jdbc=jdbc;this.transactions=transactions;this.materializer=materializer;this.clock=clock;this.lease=lease;this.batch=batch;}

    @Scheduled(fixedDelayString="${app.jobs.recurrence.fixed-delay-ms:30000}")
    public void poll(){
        var now=clock.instant();var enqueued=enqueue(now);var completed=0;var skipped=0;var failed=0;
        for(int i=0;i<batch;i++){
            var job=reserve(now,null);if(job==null)break;
            try{
                switch(process(job,now)){case COMPLETED->completed++;case SKIPPED->skipped++;case LOST->failed++;}
            }catch(RuntimeException error){fail(job,now,error.getClass().getSimpleName());failed++;}
        }
        if(enqueued+completed+skipped+failed>0)
            LOG.info("recurrence_generation enqueued={} completed={} skipped={} failed={}",enqueued,completed,skipped,failed);
    }

    public int enqueue(Instant now){return Objects.requireNonNull(transactions.execute(status->{
        var count=0;var segments=segments(null);
        for(var r:definitions(null)){
            var month=YearMonth.from(now.atZone(ZoneId.of(r.zone())));
            var due=r.schedule(segments.getOrDefault(r.id(),List.of())).occurrenceIn(month);
            if(due.isPresent())count+=insertJob(r,due.get().dueDate(),now);
        }
        return count;}));}

    @Override
    public int materializeUpcoming(UUID spaceId,LocalDate dueThrough){
        var now=clock.instant();
        transactions.executeWithoutResult(status->{var segments=segments(null);
            for(var r:definitions(null)){
                if(!r.space().equals(spaceId))continue;
                var today=now.atZone(ZoneId.of(r.zone())).toLocalDate();
                var schedule=r.schedule(segments.getOrDefault(r.id(),List.of()));
                for(var month=YearMonth.from(today);!month.isAfter(YearMonth.from(dueThrough));month=month.plusMonths(1)){
                    var due=schedule.occurrenceIn(month);
                    if(due.isPresent()&&!due.get().dueDate().isAfter(dueThrough))insertJob(r,due.get().dueDate(),now);
                }
            }});
        var completed=0;
        for(int i=0;i<batch;i++){
            var job=reserve(now,spaceId);if(job==null)break;
            try{if(process(job,now)==Outcome.COMPLETED)completed++;}
            catch(RuntimeException error){fail(job,now,error.getClass().getSimpleName());}
        }
        return completed;
    }

    // A job skipped after a change is revived if a later change brings its date back.
    private int insertJob(Definition r,LocalDate due,Instant now){return jdbc.update("""
                insert into recurrence_generation_jobs(id,recurrence_id,space_id,scheduled_due_date,status,available_at,created_at,updated_at)
                values(?,?,?,?,'PENDING',?,?,?)
                on conflict(recurrence_id,scheduled_due_date) do update set status='PENDING',available_at=excluded.available_at,
                    completed_at=null,updated_at=excluded.updated_at
                 where recurrence_generation_jobs.status='SKIPPED'
                """,UUID.randomUUID(),r.id(),r.space(),due,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}

    Outcome process(Job job,Instant now){return Objects.requireNonNull(transactions.execute(status->{
        jdbc.query("select id from recurrence_definitions where id=? and space_id=? for share",
                (rs,n)->rs.getObject(1,UUID.class),job.recurrence(),job.space());
        var definition=definitions(job.recurrence()).stream().filter(r->r.space().equals(job.space())).findFirst();
        var occurrence=definition.flatMap(r->r.schedule(segments(job.recurrence()).getOrDefault(r.id(),List.of()))
                .occurrenceIn(YearMonth.from(job.due())));
        if(occurrence.isEmpty()) return skip(job,now)?Outcome.SKIPPED:Outcome.LOST;
        var r=definition.get();var configuration=occurrence.get().segment().configuration();
        var command=new RecurringExpenseCommand(job.id(),job.token(),UUID.randomUUID(),job.recurrence(),job.space(),
                configuration.description(),configuration.amount(),r.fixed(),occurrence.get().dueDate(),
                configuration.categoryId(),configuration.responsibleUserId(),r.creator(),now);
        return materializer.materialize(command)&&complete(job,now)?Outcome.COMPLETED:Outcome.LOST;
    }));}

    private Job reserve(Instant now,UUID space){return transactions.execute(status->{var token=UUID.randomUUID();var rows=jdbc.query("""
            with candidate as (select id from recurrence_generation_jobs
              where ((status in ('PENDING','FAILED') and available_at<=?) or (status='PROCESSING' and lease_until<=?))
                and (?::uuid is null or space_id=?)
              order by available_at,id for update skip locked limit 1)
            update recurrence_generation_jobs j set status='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=?
             from candidate where j.id=candidate.id returning j.id,j.recurrence_id,j.space_id,j.scheduled_due_date
            """,(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,LocalDate.class),token),Timestamp.from(now),Timestamp.from(now),space,space,token,Timestamp.from(now.plus(lease)),Timestamp.from(now));return rows.isEmpty()?null:rows.getFirst();});}
    private boolean complete(Job job,Instant now){return jdbc.update("update recurrence_generation_jobs set status='COMPLETED',completed_at=?,lease_token=null,lease_until=null,updated_at=? where id=? and lease_token=? and status='PROCESSING'",Timestamp.from(now),Timestamp.from(now),job.id(),job.token())==1;}
    private boolean skip(Job job,Instant now){return jdbc.update("update recurrence_generation_jobs set status='SKIPPED',completed_at=?,lease_token=null,lease_until=null,updated_at=? where id=? and lease_token=? and status='PROCESSING' and lease_until>?",Timestamp.from(now),Timestamp.from(now),job.id(),job.token(),Timestamp.from(now))==1;}
    private void fail(Job job,Instant now,String code){jdbc.update("update recurrence_generation_jobs set status='FAILED',available_at=?,lease_token=null,lease_until=null,last_error_code=?,updated_at=? where id=? and lease_token=?",Timestamp.from(now.plusSeconds(30)),code,Timestamp.from(now),job.id(),job.token());LOG.warn("recurrence_generation_failed jobId={} errorCode={}",job.id(),code);}

    private List<Definition> definitions(UUID id){return jdbc.query("""
            select r.id,r.space_id,r.first_due_date,r.last_due_date,s.time_zone,r.value_type,r.created_by_user_id
              from recurrence_definitions r join family_spaces s on s.id=r.space_id
             where ?::uuid is null or r.id=?
            """,(rs,n)->new Definition(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,LocalDate.class),
                rs.getObject(4,LocalDate.class),rs.getString(5),"FIXED".equals(rs.getString(6)),rs.getObject(7,UUID.class)),id,id);}
    private Map<UUID,List<RecurrenceSegment>> segments(UUID id){return jdbc.query("""
            select recurrence_id,effective_month,description,amount,frequency,base_day,category_id,responsible_user_id,estimate_reset
              from recurrence_segments where ?::uuid is null or recurrence_id=? order by recurrence_id,effective_month
            """,(rs,n)->Map.entry(rs.getObject(1,UUID.class),new RecurrenceSegment(YearMonth.from(rs.getObject(2,LocalDate.class)),
                new RecurrenceConfiguration(rs.getString(3),rs.getBigDecimal(4),RecurrenceFrequency.valueOf(rs.getString(5)),
                        rs.getInt(6),rs.getObject(7,UUID.class),rs.getObject(8,UUID.class)),rs.getBoolean(9))),id,id)
            .stream().collect(Collectors.groupingBy(Map.Entry::getKey,Collectors.mapping(Map.Entry::getValue,Collectors.toList())));}

    enum Outcome { COMPLETED, SKIPPED, LOST }
    private record Definition(UUID id,UUID space,LocalDate first,LocalDate last,String zone,boolean fixed,UUID creator){
        RecurrenceSchedule schedule(List<RecurrenceSegment> segments){return RecurrenceSchedule.of(first,last,segments);}
    }
    record Job(UUID id,UUID recurrence,UUID space,LocalDate due,UUID token){}
}
