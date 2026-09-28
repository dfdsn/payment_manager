package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.MembershipDepartureHandler;

/** Clears only the current assignment; immutable authors and payers remain untouched. */
public final class JdbcMembershipDepartureHandler implements MembershipDepartureHandler {
    private final JdbcTemplate jdbc;

    public JdbcMembershipDepartureHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void beforeMembershipEnds(UUID spaceId, UUID departingUserId, UUID actorUserId, Instant occurredAt) {
        var affected = jdbc.query("""
                select e.id, e.version, e.description, e.charge_amount, e.due_date, e.notes,
                       e.paid_amount, e.payment_date, e.paid_by_user_id, e.payment_notes,
                       e.category_id, c.name, responsible.display_name
                  from expense_entries e
                  left join expense_categories c on c.id=e.category_id
                  join identity_users responsible on responsible.id=e.responsible_user_id
                 where e.space_id=? and e.responsible_user_id=?
                 order by e.id for update of e
                """, (rs, row) -> new Assignment(
                    rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4),
                    rs.getObject(5, java.time.LocalDate.class), rs.getString(6), rs.getBigDecimal(7),
                    rs.getObject(8, java.time.LocalDate.class), rs.getObject(9, UUID.class), rs.getString(10),
                    rs.getObject(11, UUID.class), rs.getString(12), rs.getString(13)), spaceId, departingUserId);
        for (var expense : affected) {
            jdbc.update("""
                    insert into expense_correction_events(
                        id, expense_id, space_id, actor_user_id, corrected_at, from_version, to_version, changed_fields,
                        old_description, new_description, old_charge_amount, new_charge_amount,
                        old_due_date, new_due_date, old_notes, new_notes,
                        old_paid_amount, new_paid_amount, old_payment_date, new_payment_date,
                        old_payer_user_id, new_payer_user_id, old_payment_notes, new_payment_notes,
                        old_category_id, new_category_id, old_category_name, new_category_name,
                        old_responsible_user_id, new_responsible_user_id, old_responsible_name, new_responsible_name)
                    values (?, ?, ?, ?, ?, ?, ?, 'responsibleUserId', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, null)
                    """, UUID.randomUUID(), expense.id(), spaceId, actorUserId, Timestamp.from(occurredAt),
                    expense.version(), expense.version() + 1, expense.description(), expense.description(),
                    expense.amount(), expense.amount(), expense.dueDate(), expense.dueDate(), expense.notes(), expense.notes(),
                    expense.paidAmount(), expense.paidAmount(), expense.paymentDate(), expense.paymentDate(),
                    expense.payerId(), expense.payerId(), expense.paymentNotes(), expense.paymentNotes(),
                    expense.categoryId(), expense.categoryId(), expense.categoryName(), expense.categoryName(),
                    departingUserId, expense.responsibleName());
            jdbc.update("""
                    update expense_entries set responsible_user_id=null, version=version+1
                     where id=? and space_id=? and version=? and responsible_user_id=?
                    """, expense.id(), spaceId, expense.version(), departingUserId);
        }
    }

    private record Assignment(UUID id, long version, String description, java.math.BigDecimal amount,
            java.time.LocalDate dueDate, String notes, java.math.BigDecimal paidAmount,
            java.time.LocalDate paymentDate, UUID payerId, String paymentNotes, UUID categoryId,
            String categoryName, String responsibleName) { }
}
