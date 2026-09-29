package com.malyah.accountmanager.expenses.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseReminderQueries;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.ReminderExpense;
import com.malyah.accountmanager.expenses.application.ReminderExpenseState;

/**
 * H08.2 read of the pending expenses of a space due up to a date, ordered by due date and id; H08.3 current state of
 * the expenses a summary mentioned.
 */
public final class JdbcExpenseReminderQueries implements ExpenseReminderQueries {
    private final JdbcTemplate jdbc;

    public JdbcExpenseReminderQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReminderExpense> pendingDueThrough(UUID spaceId, LocalDate dueThrough) {
        return jdbc.query("""
                select e.id, e.origin, e.installment_purchase_id, e.installment_number, e.installment_count,
                       e.description, e.due_date, e.charge_amount, e.charge_confirmed
                  from expense_entries e
                 where e.space_id = ? and e.status = 'PENDING' and e.due_date is not null and e.due_date <= ?
                 order by e.due_date, e.id
                """, (rs, row) -> {
                    var purchase = rs.getObject(3, UUID.class);
                    return new ReminderExpense(rs.getObject(1, UUID.class), rs.getString(2),
                            purchase == null ? null : new InstallmentLink(purchase, rs.getInt(4), rs.getInt(5)),
                            rs.getString(6), rs.getObject(7, LocalDate.class), rs.getBigDecimal(8), rs.getBoolean(9));
                }, spaceId, dueThrough);
    }

    @Override
    public List<ReminderExpenseState> currentStates(UUID spaceId, Collection<UUID> expenseIds) {
        if (expenseIds.isEmpty()) return List.of();
        return jdbc.query("""
                select id, status, due_date from expense_entries where space_id = ? and id = any(?)
                """, (rs, row) -> new ReminderExpenseState(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, LocalDate.class)), spaceId, expenseIds.toArray(UUID[]::new));
    }
}
