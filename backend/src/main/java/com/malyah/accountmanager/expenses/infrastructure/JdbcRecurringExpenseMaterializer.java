package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.RecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;

public final class JdbcRecurringExpenseMaterializer implements RecurringExpenseMaterializer {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    public JdbcRecurringExpenseMaterializer(JdbcTemplate jdbc, TransactionTemplate transactions){this.jdbc=jdbc;this.transactions=transactions;}
    @Override public boolean materialize(RecurringExpenseCommand c) {
        return Objects.requireNonNull(transactions.execute(status -> materializeInTransaction(c)));
    }
    private boolean materializeInTransaction(RecurringExpenseCommand c) {
        var leased=jdbc.queryForObject("""
            select count(*) from recurrence_generation_jobs
             where id=? and lease_token=? and status='PROCESSING' and lease_until>?
            """,Integer.class,c.jobId(),c.leaseToken(),Timestamp.from(c.generatedAt()));
        if(leased==null||leased!=1) return false;
        var claimed=jdbc.update("""
            insert into recurrence_occurrences(id,recurrence_id,space_id,scheduled_due_date,created_at)
            values(?,?,?,?,?) on conflict(recurrence_id,scheduled_due_date) do nothing
            """,c.occurrenceId(),c.recurrenceId(),c.spaceId(),c.scheduledDueDate(),Timestamp.from(c.generatedAt()));
        if(claimed==0) return true;
        var expenseId=UUID.randomUUID();
        var category=eligibleCategory(c.spaceId(),c.categoryId());
        var responsible=eligibleResponsible(c.spaceId(),c.responsibleUserId());
        jdbc.update("""
            insert into expense_entries(id,space_id,origin,description,charge_amount,charge_confirmed,status,
                due_date,reference_date,created_by_user_id,created_at,version,category_id,responsible_user_id)
            values(?,?,'RECURRENCE',?,?,?,'PENDING',?,?,?,?,0,?,?)
            """,expenseId,c.spaceId(),c.description(),c.amount(),c.chargeConfirmed(),c.scheduledDueDate(),
                c.scheduledDueDate(),c.createdByUserId(),Timestamp.from(c.generatedAt()),category,responsible);
        jdbc.update("update recurrence_occurrences set expense_id=? where id=?",expenseId,c.occurrenceId());
        jdbc.update("""
            insert into recurrence_occurrence_events(id,occurrence_id,recurrence_id,expense_id,space_id,event_type,occurred_at)
            values(?,?,?,?,?,'OCCURRENCE_MATERIALIZED',?)
            """,UUID.randomUUID(),c.occurrenceId(),c.recurrenceId(),expenseId,c.spaceId(),Timestamp.from(c.generatedAt()));
        return true;
    }
    private UUID eligibleCategory(UUID space,UUID id){if(id==null)return null;return jdbc.query("select id from expense_categories where id=? and space_id=? and archived_at is null",(r,n)->r.getObject(1,UUID.class),id,space).stream().findFirst().orElse(null);}
    private UUID eligibleResponsible(UUID space,UUID id){if(id==null)return null;return jdbc.query("select user_id from space_memberships where user_id=? and space_id=? and active=true",(r,n)->r.getObject(1,UUID.class),id,space).stream().findFirst().orElse(null);}
}
