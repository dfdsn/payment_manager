package com.malyah.accountmanager.recurrences.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;

@Testcontainers
class RecurrenceGenerationPostgresIT {
    private static final Instant NOW=Instant.parse("2026-09-27T15:00:00Z");
    private static final UUID SPACE=UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN=UUID.fromString("30000000-0000-0000-0000-000000000002");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_generation_test").withUsername("account_manager").withPassword("test-only-password");
    private JdbcTemplate jdbc; private JdbcRecurrenceGenerationJob job;

    @BeforeEach void reset(){
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var flyway=Flyway.configure().dataSource(ds).cleanDisabled(false).load();flyway.clean();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(24);
        jdbc=new JdbcTemplate(ds);insertSpaceAndUser();var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        job=new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(2),25);
    }

    @Test void generatesOnlyCurrentEligibleFixedAndVariableOccurrencesAndReplaysWithoutDuplicates(){
        insertRecurrence("Fixa","100.00","FIXED","MONTHLY",LocalDate.of(2026,1,30),null,null,null);
        insertRecurrence("Variável","80.00","VARIABLE_ESTIMATE","BIMONTHLY",LocalDate.of(2026,1,15),null,null,null);
        insertRecurrence("Futura","50.00","FIXED","MONTHLY",LocalDate.of(2026,10,1),null,null,null);
        insertRecurrence("Encerrada","40.00","FIXED","MONTHLY",LocalDate.of(2026,1,1),LocalDate.of(2026,8,1),null,null);
        job.poll();job.poll();
        assertThat(jdbc.query("select description,charge_confirmed,due_date,origin from expense_entries order by description",
                (r,n)->r.getString(1)+":"+r.getBoolean(2)+":"+r.getObject(3,LocalDate.class)+":"+r.getString(4)))
                .containsExactly("Fixa:true:2026-09-30:RECURRENCE","Variável:false:2026-09-15:RECURRENCE");
        assertThat(jdbc.queryForObject("select count(*) from recurrence_occurrences",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_occurrence_events",Integer.class)).isEqualTo(2);
    }

    @Test void concurrentWorkersAndChangedOrCancelledExpenseNeverRecreateTheLogicalOccurrence() throws Exception {
        var recurrence=insertRecurrence("Internet","120","FIXED","MONTHLY",LocalDate.of(2026,1,5),null,null,null);
        try(var pool=Executors.newFixedThreadPool(2)){var tasks=java.util.List.of((java.util.concurrent.Callable<Void>)()->{job.poll();return null;},()->{job.poll();return null;});pool.invokeAll(tasks).forEach(f->{try{f.get();}catch(Exception e){throw new AssertionError(e);}});}
        var expense=jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id=?",UUID.class,recurrence);
        jdbc.update("update expense_entries set due_date='2026-09-09',reference_date='2026-09-09',status='CANCELLED',cancelled_at=?,cancelled_by_user_id=?,cancellation_reason='teste',version=1 where id=?",Timestamp.from(NOW),ADMIN,expense);
        jdbc.update("update recurrence_generation_jobs set status='PENDING',completed_at=null,available_at=?,lease_token=null,lease_until=null where recurrence_id=?",Timestamp.from(NOW),recurrence);
        job.poll();
        assertThat(jdbc.queryForObject("select count(*) from expense_entries where origin='RECURRENCE'",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select due_date from expense_entries where id=?",LocalDate.class,expense)).isEqualTo(LocalDate.of(2026,9,9));
        assertThat(jdbc.queryForObject("select status from expense_entries where id=?",String.class,expense)).isEqualTo("CANCELLED");
    }

    @Test void expiredLeaseIsRecoveredButOldTokenCannotMaterializeAndReferencesBecomeSafeFallbacks(){
        var category=insertArchivedCategory();var guest=insertInactiveGuest();insertRecurrence("Água","90","FIXED","MONTHLY",LocalDate.of(2026,1,10),null,category,guest);
        job.enqueue(NOW);var id=jdbc.queryForObject("select id from recurrence_generation_jobs",UUID.class);
        jdbc.update("update recurrence_generation_jobs set status='PROCESSING',lease_token=?,lease_until=?,attempts=1",UUID.randomUUID(),Timestamp.from(NOW.minusSeconds(1)));
        job.poll();
        assertThat(jdbc.queryForObject("select attempts from recurrence_generation_jobs where id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select category_id from expense_entries",UUID.class)).isNull();
        assertThat(jdbc.queryForObject("select responsible_user_id from expense_entries",UUID.class)).isNull();
    }

    @Test void persistenceFailureRollsBackExpenseOccurrenceAndAuditThenRetryCompletes(){
        insertRecurrence("Falha","33","FIXED","MONTHLY",LocalDate.of(2026,1,20),null,null,null);
        jdbc.execute("alter table recurrence_occurrence_events add constraint force_failure check(false)");job.poll();
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from recurrence_occurrences",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from recurrence_generation_jobs",String.class)).isEqualTo("FAILED");
        jdbc.execute("alter table recurrence_occurrence_events drop constraint force_failure");
        jdbc.update("update recurrence_generation_jobs set available_at=?",Timestamp.from(NOW));job.poll();
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select status from recurrence_generation_jobs",String.class)).isEqualTo("COMPLETED");
    }

    private UUID insertRecurrence(String description,String amount,String type,String frequency,LocalDate first,LocalDate last,UUID category,UUID responsible){var id=UUID.randomUUID();jdbc.update("""
        insert into recurrence_definitions(id,space_id,description,amount,value_type,frequency,first_due_date,base_day,last_due_date,category_id,responsible_user_id,created_by_user_id,created_at,version)
        values(?,?,?,?,?,?,?,?,?,?,?,?,?,0)
        """,id,SPACE,description,new java.math.BigDecimal(amount),type,frequency,first,first.getDayOfMonth(),last,category,responsible,ADMIN,Timestamp.from(NOW));
        // Since V19 every definition has its initial segment, as the application creates it.
        jdbc.update("""
        insert into recurrence_segments(id,recurrence_id,space_id,effective_month,base_day,frequency,description,amount,estimate_reset,category_id,responsible_user_id,created_at)
        values(?,?,?,?,?,?,?,?,true,?,?,?)
        """,UUID.randomUUID(),id,SPACE,first.withDayOfMonth(1),first.getDayOfMonth(),frequency,description,new java.math.BigDecimal(amount),category,responsible,Timestamp.from(NOW));return id;}
    private void insertSpaceAndUser(){jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values (?, 'Casa','BRL','pt-BR','America/Sao_Paulo',?)",SPACE,Timestamp.from(NOW));jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,'Admin','admin@example.com','{test}x',true,?)",ADMIN,Timestamp.from(NOW));jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values(?,?,?,'ADMINISTRATOR',true,?)",UUID.randomUUID(),ADMIN,SPACE,Timestamp.from(NOW));}
    private UUID insertArchivedCategory(){var id=UUID.randomUUID();jdbc.update("insert into expense_categories(id,space_id,name,normalized_name,archived_at,version,created_by_user_id,created_at,updated_at) values(?,?, 'Antiga','antiga',?,1,?,?,?)",id,SPACE,Timestamp.from(NOW),ADMIN,Timestamp.from(NOW),Timestamp.from(NOW));return id;}
    private UUID insertInactiveGuest(){var id=UUID.randomUUID();jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,'Saiu','saiu@example.com','{test}x',true,?)",id,Timestamp.from(NOW));jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at,ended_at,ended_by_user_id,end_reason) values(?,?,?,'GUEST',false,?,?,?,'ADMIN_REMOVAL')",UUID.randomUUID(),id,SPACE,Timestamp.from(NOW),Timestamp.from(NOW),ADMIN);return id;}
}
