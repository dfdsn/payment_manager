package com.malyah.accountmanager.recurrences.infrastructure;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.RecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.recurrences.domain.*;

public final class JdbcRecurrenceGenerationJob {
    private static final Logger LOG=LoggerFactory.getLogger(JdbcRecurrenceGenerationJob.class);
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final RecurringExpenseMaterializer materializer;
    private final Clock clock; private final RecurrenceCalendar calendar; private final Duration lease; private final int batch;
    public JdbcRecurrenceGenerationJob(JdbcTemplate jdbc,TransactionTemplate transactions,RecurringExpenseMaterializer materializer,
            Clock clock,RecurrenceCalendar calendar,Duration lease,int batch){this.jdbc=jdbc;this.transactions=transactions;this.materializer=materializer;this.clock=clock;this.calendar=calendar;this.lease=lease;this.batch=batch;}

    @Scheduled(fixedDelayString="${app.jobs.recurrence.fixed-delay-ms:30000}")
    public void poll(){var now=clock.instant();var enqueued=enqueue(now);var completed=0;var failed=0;for(int i=0;i<batch;i++){var job=reserve(now);if(job==null)break;try{var data=load(job,now);if(materializer.materialize(data)&&complete(job,now))completed++;else failed++;}catch(RuntimeException error){fail(job,now,error.getClass().getSimpleName());failed++;}}if(enqueued+completed+failed>0)LOG.info("recurrence_generation enqueued={} completed={} failed={}",enqueued,completed,failed);}

    public int enqueue(Instant now){return Objects.requireNonNull(transactions.execute(status->{var count=0;for(var r:definitions()){var month=YearMonth.from(now.atZone(ZoneId.of(r.zone())));var due=calendar.occurrenceInMonth(r.first(),r.last(),r.frequency(),month);if(due.isPresent())count+=jdbc.update("""
                insert into recurrence_generation_jobs(id,recurrence_id,space_id,scheduled_due_date,status,available_at,created_at,updated_at)
                values(?,?,?,?,'PENDING',?,?,?) on conflict(recurrence_id,scheduled_due_date) do nothing
                """,UUID.randomUUID(),r.id(),r.space(),due.get(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}return count;}));}
    private Job reserve(Instant now){return transactions.execute(status->{var token=UUID.randomUUID();var rows=jdbc.query("""
            with candidate as (select id from recurrence_generation_jobs
              where ((status in ('PENDING','FAILED') and available_at<=?) or (status='PROCESSING' and lease_until<=?))
              order by available_at,id for update skip locked limit 1)
            update recurrence_generation_jobs j set status='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=?
             from candidate where j.id=candidate.id returning j.id,j.recurrence_id,j.space_id,j.scheduled_due_date
            """,(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,LocalDate.class),token),Timestamp.from(now),Timestamp.from(now),token,Timestamp.from(now.plus(lease)),Timestamp.from(now));return rows.isEmpty()?null:rows.getFirst();});}
    private RecurringExpenseCommand load(Job job,Instant now){return jdbc.queryForObject("""
            select r.description,r.amount,r.value_type,r.category_id,r.responsible_user_id,r.created_by_user_id
              from recurrence_definitions r where r.id=? and r.space_id=?
            """,(rs,n)->new RecurringExpenseCommand(job.id(),job.token(),UUID.randomUUID(),job.recurrence(),job.space(),rs.getString(1),rs.getBigDecimal(2),"FIXED".equals(rs.getString(3)),job.due(),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),now),job.recurrence(),job.space());}
    private boolean complete(Job job,Instant now){return jdbc.update("update recurrence_generation_jobs set status='COMPLETED',completed_at=?,lease_token=null,lease_until=null,updated_at=? where id=? and lease_token=? and status='PROCESSING'",Timestamp.from(now),Timestamp.from(now),job.id(),job.token())==1;}
    private void fail(Job job,Instant now,String code){jdbc.update("update recurrence_generation_jobs set status='FAILED',available_at=?,lease_token=null,lease_until=null,last_error_code=?,updated_at=? where id=? and lease_token=?",Timestamp.from(now.plusSeconds(30)),code,Timestamp.from(now),job.id(),job.token());LOG.warn("recurrence_generation_failed jobId={} errorCode={}",job.id(),code);}
    private java.util.List<Definition> definitions(){return jdbc.query("""
            select r.id,r.space_id,r.first_due_date,r.last_due_date,r.frequency,s.time_zone
              from recurrence_definitions r join family_spaces s on s.id=r.space_id
            """,(rs,n)->new Definition(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),RecurrenceFrequency.valueOf(rs.getString(5)),rs.getString(6)));}
    private record Definition(UUID id,UUID space,LocalDate first,LocalDate last,RecurrenceFrequency frequency,String zone){}
    private record Job(UUID id,UUID recurrence,UUID space,LocalDate due,UUID token){}
}
