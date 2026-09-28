package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.expenses.application.ExpenseStateConflictException;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjuster;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjustment;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceSnapshot;

/**
 * H04.5: applies a recurrence change to its pending launches. Updates are recorded as corrections and removals as
 * cancellations, both linked to the recurrence change, so the launch history keeps the author, instant and values.
 */
public final class JdbcRecurringOccurrenceAdjuster implements RecurringOccurrenceAdjuster {
    private final JdbcTemplate jdbc;

    public JdbcRecurringOccurrenceAdjuster(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<RecurringOccurrenceSnapshot> occurrences(UUID spaceId, UUID recurrenceId, YearMonth lockFrom) {
        if (lockFrom != null) jdbc.query("""
                select e.id from expense_entries e
                  join recurrence_occurrences o on o.expense_id=e.id and o.space_id=e.space_id
                 where o.recurrence_id=? and o.space_id=? and o.scheduled_month>=?
                 order by e.id for update of e
                """, (rs, row) -> rs.getObject(1, UUID.class), recurrenceId, spaceId, lockFrom.atDay(1));
        return jdbc.query("""
                select e.id, o.scheduled_due_date, e.version, e.status, e.charge_confirmed, e.charge_amount,
                       e.due_date, e.description, e.category_id, e.responsible_user_id,
                       exists(select 1 from expense_correction_events c
                               where c.expense_id=e.id and c.recurrence_change_id is null
                                 and (',' || c.changed_fields || ',') like '%,dueDate,%')
                  from recurrence_occurrences o
                  join expense_entries e on e.id=o.expense_id and e.space_id=o.space_id
                 where o.recurrence_id=? and o.space_id=?
                 order by o.scheduled_due_date
                """, (rs, row) -> new RecurringOccurrenceSnapshot(rs.getObject(1, UUID.class),
                    rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4), rs.getBoolean(5),
                    rs.getBigDecimal(6), rs.getObject(7, LocalDate.class), rs.getString(8), rs.getObject(9, UUID.class),
                    rs.getObject(10, UUID.class), rs.getBoolean(11)), recurrenceId, spaceId);
    }

    @Override
    public void apply(UUID spaceId, UUID actorId, UUID changeId, Instant at, List<RecurringOccurrenceAdjustment> adjustments) {
        for (var adjustment : adjustments) {
            if (adjustment.remove()) remove(spaceId, actorId, changeId, at, adjustment);
            else update(spaceId, actorId, changeId, at, adjustment);
        }
    }

    private void update(UUID spaceId, UUID actorId, UUID changeId, Instant at, RecurringOccurrenceAdjustment a) {
        var current = jdbc.query("""
                select e.description, e.charge_amount, e.due_date, e.notes, e.category_id, c.name,
                       e.responsible_user_id, responsible.display_name
                  from expense_entries e
                  left join expense_categories c on c.id=e.category_id
                  left join identity_users responsible on responsible.id=e.responsible_user_id
                 where e.id=? and e.space_id=? and e.version=? and e.status='PENDING'
                """, (rs, row) -> new Current(rs.getString(1), rs.getBigDecimal(2), rs.getObject(3, LocalDate.class),
                    rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class),
                    rs.getString(8)), a.expenseId(), spaceId, a.expectedVersion()).stream().findFirst()
                .orElseThrow(ExpenseStateConflictException::new);
        var updated = jdbc.update("""
                update expense_entries set description=?, charge_amount=?, due_date=?, reference_date=?,
                    category_id=?, responsible_user_id=?, version=version+1
                 where id=? and space_id=? and version=? and status='PENDING'
                """, a.description(), a.amount(), a.dueDate(), a.dueDate(), a.categoryId(), a.responsibleUserId(),
                a.expenseId(), spaceId, a.expectedVersion());
        if (updated != 1) throw new ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_correction_events(
                    id, expense_id, space_id, actor_user_id, corrected_at, from_version, to_version, changed_fields,
                    old_description, new_description, old_charge_amount, new_charge_amount,
                    old_due_date, new_due_date, old_notes, new_notes,
                    old_category_id, new_category_id, old_category_name, new_category_name,
                    old_responsible_user_id, new_responsible_user_id, old_responsible_name, new_responsible_name,
                    recurrence_change_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    (select name from expense_categories where id=?), ?, ?, ?,
                    (select display_name from identity_users where id=?), ?)
                """, UUID.randomUUID(), a.expenseId(), spaceId, actorId, Timestamp.from(at), a.expectedVersion(),
                a.expectedVersion() + 1, String.join(",", a.changedFields()), current.description(), a.description(),
                current.amount(), a.amount(), current.dueDate(), a.dueDate(), current.notes(), current.notes(),
                current.categoryId(), a.categoryId(), current.categoryName(), a.categoryId(),
                current.responsibleUserId(), a.responsibleUserId(), current.responsibleName(), a.responsibleUserId(),
                changeId);
    }

    private void remove(UUID spaceId, UUID actorId, UUID changeId, Instant at, RecurringOccurrenceAdjustment a) {
        var updated = jdbc.update("""
                update expense_entries set status='CANCELLED', cancelled_at=?, cancelled_by_user_id=?,
                    cancellation_reason=?, version=version+1
                 where id=? and space_id=? and version=? and status='PENDING'
                """, Timestamp.from(at), actorId, a.removalReason(), a.expenseId(), spaceId, a.expectedVersion());
        if (updated != 1) throw new ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_cancellation_events(
                    id, expense_id, space_id, actor_user_id, reason, cancelled_at, from_version, to_version,
                    recurrence_change_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), a.expenseId(), spaceId, actorId, a.removalReason(), Timestamp.from(at),
                a.expectedVersion(), a.expectedVersion() + 1, changeId);
    }

    private record Current(String description, java.math.BigDecimal amount, LocalDate dueDate, String notes,
            UUID categoryId, String categoryName, UUID responsibleUserId, String responsibleName) { }
}
