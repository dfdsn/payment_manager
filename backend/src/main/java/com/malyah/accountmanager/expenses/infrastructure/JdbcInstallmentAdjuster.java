package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.expenses.application.ExpenseStateConflictException;
import com.malyah.accountmanager.expenses.application.InstallmentAdjuster;
import com.malyah.accountmanager.expenses.application.InstallmentAdjustment;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;

/**
 * H05.3: applies a purchase change to its pending installments. Updates are recorded as corrections and
 * cancellations as ordinary cancellations, both linked to the installment change, so each installment's history keeps
 * the author, instant, reason and old/new values. Paid and cancelled installments are never touched.
 */
public final class JdbcInstallmentAdjuster implements InstallmentAdjuster {
    private final JdbcTemplate jdbc;
    private final JdbcInstallmentExpenses reader;

    public JdbcInstallmentAdjuster(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reader = new JdbcInstallmentExpenses(jdbc, UUID::randomUUID);
    }

    @Override
    public List<InstallmentExpenseSnapshot> lock(UUID spaceId, UUID purchaseId) {
        jdbc.query("""
                select id from expense_entries
                 where space_id=? and installment_purchase_id=? and origin='INSTALLMENT'
                 order by id for update
                """, (rs, row) -> rs.getObject(1, UUID.class), spaceId, purchaseId);
        return reader.find(spaceId, purchaseId);
    }

    @Override
    public void apply(UUID spaceId, UUID actorId, UUID changeId, Instant at, List<InstallmentAdjustment> adjustments) {
        for (var adjustment : adjustments) {
            if (adjustment.cancel()) cancel(spaceId, actorId, changeId, at, adjustment);
            else update(spaceId, actorId, changeId, at, adjustment);
        }
    }

    private void update(UUID spaceId, UUID actorId, UUID changeId, Instant at, InstallmentAdjustment a) {
        var current = jdbc.query("""
                select e.description, e.charge_amount, e.due_date, e.notes, e.category_id, c.name,
                       e.responsible_user_id, responsible.display_name
                  from expense_entries e
                  left join expense_categories c on c.id=e.category_id
                  left join identity_users responsible on responsible.id=e.responsible_user_id
                 where e.id=? and e.space_id=? and e.version=? and e.status='PENDING' and e.origin='INSTALLMENT'
                """, (rs, row) -> new Current(rs.getString(1), rs.getBigDecimal(2), rs.getObject(3, LocalDate.class),
                    rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class),
                    rs.getString(8)), a.expenseId(), spaceId, a.expectedVersion()).stream().findFirst()
                .orElseThrow(ExpenseStateConflictException::new);
        var updated = jdbc.update("""
                update expense_entries set description=?, due_date=?, reference_date=?, category_id=?,
                    responsible_user_id=?, version=version+1
                 where id=? and space_id=? and version=? and status='PENDING' and origin='INSTALLMENT'
                """, a.description(), a.dueDate(), a.dueDate(), a.categoryId(), a.responsibleUserId(), a.expenseId(),
                spaceId, a.expectedVersion());
        if (updated != 1) throw new ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_correction_events(
                    id, expense_id, space_id, actor_user_id, corrected_at, from_version, to_version, changed_fields,
                    old_description, new_description, old_charge_amount, new_charge_amount,
                    old_due_date, new_due_date, old_notes, new_notes,
                    old_category_id, new_category_id, old_category_name, new_category_name,
                    old_responsible_user_id, new_responsible_user_id, old_responsible_name, new_responsible_name,
                    installment_change_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    (select name from expense_categories where id=?), ?, ?, ?,
                    (select display_name from identity_users where id=?), ?)
                """, UUID.randomUUID(), a.expenseId(), spaceId, actorId, Timestamp.from(at), a.expectedVersion(),
                a.expectedVersion() + 1, String.join(",", a.changedFields()), current.description(), a.description(),
                current.amount(), current.amount(), current.dueDate(), a.dueDate(), current.notes(), current.notes(),
                current.categoryId(), a.categoryId(), current.categoryName(), a.categoryId(),
                current.responsibleUserId(), a.responsibleUserId(), current.responsibleName(), a.responsibleUserId(),
                changeId);
    }

    private void cancel(UUID spaceId, UUID actorId, UUID changeId, Instant at, InstallmentAdjustment a) {
        var updated = jdbc.update("""
                update expense_entries set status='CANCELLED', cancelled_at=?, cancelled_by_user_id=?,
                    cancellation_reason=?, version=version+1
                 where id=? and space_id=? and version=? and status='PENDING' and origin='INSTALLMENT'
                """, Timestamp.from(at), actorId, a.cancellationReason(), a.expenseId(), spaceId, a.expectedVersion());
        if (updated != 1) throw new ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_cancellation_events(
                    id, expense_id, space_id, actor_user_id, reason, cancelled_at, from_version, to_version,
                    installment_change_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), a.expenseId(), spaceId, actorId, a.cancellationReason(), Timestamp.from(at),
                a.expectedVersion(), a.expectedVersion() + 1, changeId);
    }

    private record Current(String description, java.math.BigDecimal amount, LocalDate dueDate, String notes,
            UUID categoryId, String categoryName, UUID responsibleUserId, String responsibleName) { }
}
