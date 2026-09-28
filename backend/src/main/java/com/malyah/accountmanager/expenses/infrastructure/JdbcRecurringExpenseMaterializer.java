package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.RecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.application.AnticipatedRecurringExpenseCommand;

public final class JdbcRecurringExpenseMaterializer implements RecurringExpenseMaterializer {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    public JdbcRecurringExpenseMaterializer(JdbcTemplate jdbc, TransactionTemplate transactions){this.jdbc=jdbc;this.transactions=transactions;}
    @Override public boolean materialize(RecurringExpenseCommand c) {
        return Objects.requireNonNull(transactions.execute(status -> materializeInTransaction(c)));
    }
    @Override public UUID materializeAnticipated(AnticipatedRecurringExpenseCommand c) {
        return transactions.execute(status -> materializeCore(c.occurrenceId(),c.recurrenceId(),c.spaceId(),
                c.description(),c.amount(),c.chargeConfirmed(),c.scheduledDueDate(),c.categoryId(),
                c.responsibleUserId(),c.createdByUserId(),c.generatedAt()));
    }
    private boolean materializeInTransaction(RecurringExpenseCommand c) {
        var leased=jdbc.queryForObject("""
            select count(*) from recurrence_generation_jobs
             where id=? and lease_token=? and status='PROCESSING' and lease_until>?
            """,Integer.class,c.jobId(),c.leaseToken(),Timestamp.from(c.generatedAt()));
        if(leased==null||leased!=1) return false;
        materializeCore(c.occurrenceId(),c.recurrenceId(),c.spaceId(),c.description(),c.amount(),c.chargeConfirmed(),
                c.scheduledDueDate(),c.categoryId(),c.responsibleUserId(),c.createdByUserId(),c.generatedAt());
        return true;
    }
    private UUID materializeCore(UUID occurrenceId,UUID recurrenceId,UUID spaceId,String description,
            java.math.BigDecimal amount,boolean confirmed,java.time.LocalDate due,UUID categoryId,
            UUID responsibleId,UUID creatorId,java.time.Instant at) {
        var charge=confirmed?amount:referenceEstimate(recurrenceId,spaceId,amount,due);
        var claimed=jdbc.update("""
            insert into recurrence_occurrences(id,recurrence_id,space_id,scheduled_due_date,created_at)
            values(?,?,?,?,?) on conflict(recurrence_id,scheduled_due_date) do nothing
            """,occurrenceId,recurrenceId,spaceId,due,Timestamp.from(at));
        if(claimed==0) return jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id=? and scheduled_due_date=?",UUID.class,recurrenceId,due);
        var expenseId=UUID.randomUUID();
        var category=eligibleCategory(spaceId,categoryId);
        var responsible=eligibleResponsible(spaceId,responsibleId);
        jdbc.update("""
            insert into expense_entries(id,space_id,origin,description,charge_amount,charge_confirmed,status,
                due_date,reference_date,created_by_user_id,created_at,version,category_id,responsible_user_id)
            values(?,?,'RECURRENCE',?,?,?,'PENDING',?,?,?,?,0,?,?)
            """,expenseId,spaceId,description,charge,confirmed,due,due,creatorId,Timestamp.from(at),category,responsible);
        jdbc.update("update recurrence_occurrences set expense_id=? where id=?",expenseId,occurrenceId);
        jdbc.update("""
            insert into recurrence_occurrence_events(id,occurrence_id,recurrence_id,expense_id,space_id,event_type,occurred_at)
            values(?,?,?,?,?,'OCCURRENCE_MATERIALIZED',?)
            """,UUID.randomUUID(),occurrenceId,recurrenceId,expenseId,spaceId,Timestamp.from(at));
        return expenseId;
    }
    /**
     * H04.4: a new variable occurrence starts from the latest confirmed charge scheduled before it. The share lock
     * serializes with confirmations, which update the definition row before refreshing materialized estimates.
     */
    private java.math.BigDecimal referenceEstimate(UUID recurrenceId,UUID spaceId,java.math.BigDecimal initial,
            java.time.LocalDate due) {
        jdbc.query("select id from recurrence_definitions where id=? and space_id=? for share",
                (r,n)->r.getObject(1,UUID.class),recurrenceId,spaceId);
        var confirmed=jdbc.query("""
            select o.scheduled_due_date,e.charge_amount from recurrence_occurrences o
              join expense_entries e on e.id=o.expense_id and e.space_id=o.space_id
             where o.recurrence_id=? and o.space_id=? and e.charge_confirmed=true
            """,(r,n)->new com.malyah.accountmanager.expenses.domain.VariableEstimateReference.ConfirmedCharge(
                r.getObject(1,java.time.LocalDate.class),r.getBigDecimal(2)),recurrenceId,spaceId);
        return com.malyah.accountmanager.expenses.domain.VariableEstimateReference.estimateFor(initial,confirmed,due);
    }
    private UUID eligibleCategory(UUID space,UUID id){if(id==null)return null;return jdbc.query("select id from expense_categories where id=? and space_id=? and archived_at is null",(r,n)->r.getObject(1,UUID.class),id,space).stream().findFirst().orElse(null);}
    private UUID eligibleResponsible(UUID space,UUID id){if(id==null)return null;return jdbc.query("select user_id from space_memberships where user_id=? and space_id=? and active=true",(r,n)->r.getObject(1,UUID.class),id,space).stream().findFirst().orElse(null);}
}
