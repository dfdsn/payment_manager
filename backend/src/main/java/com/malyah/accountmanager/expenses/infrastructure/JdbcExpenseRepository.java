package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseIdempotencyConflictException;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.StoredExpense;
import com.malyah.accountmanager.expenses.application.StoredExpenseCreation;
import com.malyah.accountmanager.expenses.application.StoredExpensePage;
import com.malyah.accountmanager.expenses.application.port.ExpenseRepository;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;

final class JdbcExpenseRepository implements ExpenseRepository {
    private static final String OPERATION = "CREATE_ONE_OFF_EXPENSE";
    private final JdbcTemplate jdbc;

    JdbcExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredExpenseCreation createIdempotently(
            OneOffExpense expense, UUID actorUserId, UUID key, String requestHash, Instant requestedAt) {
        var claimed = jdbc.update("""
                insert into expense_idempotency_requests(
                    space_id, actor_user_id, operation, idempotency_key, request_hash, created_at
                ) values (?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """, expense.spaceId(), actorUserId, OPERATION, key, requestHash, Timestamp.from(requestedAt));

        if (claimed == 0) {
            var existing = jdbc.queryForObject("""
                    select request_hash, expense_id
                      from expense_idempotency_requests
                     where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ?
                     for update
                    """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                    expense.spaceId(), actorUserId, OPERATION, key);
            if (existing == null || !requestHash.equals(existing.requestHash()) || existing.expenseId() == null) {
                throw new ExpenseIdempotencyConflictException();
            }
            return new StoredExpenseCreation(find(expense.spaceId(), existing.expenseId()), true);
        }

        insert(expense);
        jdbc.update("""
                update expense_idempotency_requests
                   set expense_id = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ?
                """, expense.id(), Timestamp.from(expense.createdAt()), expense.spaceId(), actorUserId, OPERATION, key);
        return new StoredExpenseCreation(find(expense.spaceId(), expense.id()), false);
    }

    @Override
    public StoredExpensePage findBySpace(UUID spaceId, ExpenseListQuery query) {
        var total = jdbc.queryForObject(
                "select count(*) from expense_entries where space_id = ?", Long.class, spaceId);
        var order = switch (query.sort()) {
            case REFERENCE_DATE -> "e.reference_date";
            case AMOUNT -> "e.charge_amount";
            case DESCRIPTION -> "lower(e.description)";
        };
        var direction = query.direction().name();
        var items = jdbc.query(selectBase() + " where e.space_id = ? order by " + order + " " + direction
                        + ", e.created_at " + direction + ", e.id " + direction + " limit ? offset ?",
                this::map, spaceId, query.size(), (long) query.page() * query.size());
        return new StoredExpensePage(items, total == null ? 0 : total);
    }

    private void insert(OneOffExpense expense) {
        var paid = expense.status() == ExpenseStatus.PAID;
        jdbc.update("""
                insert into expense_entries(
                    id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, notes, paid_amount, payment_date, paid_by_user_id,
                    payment_recorded_by_user_id, created_by_user_id, created_at, version
                ) values (?, ?, 'ONE_OFF', ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, expense.id(), expense.spaceId(), expense.description(), expense.amount().value(),
                expense.status().name(), expense.dueDate(), expense.referenceDate(), expense.notes(),
                paid ? expense.amount().value() : null, expense.paymentDate(),
                paid ? expense.createdByUserId() : null, paid ? expense.createdByUserId() : null,
                expense.createdByUserId(), Timestamp.from(expense.createdAt()));
    }

    private StoredExpense find(UUID spaceId, UUID expenseId) {
        var results = jdbc.query(selectBase() + " where e.space_id = ? and e.id = ?", this::map, spaceId, expenseId);
        if (results.isEmpty()) throw new IllegalStateException("Despesa idempotente não encontrada.");
        return results.getFirst();
    }

    private String selectBase() {
        return """
                select e.id, e.space_id, e.description, e.charge_amount, e.status, e.due_date,
                       e.payment_date, e.paid_amount, e.notes, e.created_by_user_id,
                       creator.display_name, e.paid_by_user_id, payer.display_name, e.created_at, e.version
                  from expense_entries e
                  join identity_users creator on creator.id = e.created_by_user_id
                  left join identity_users payer on payer.id = e.paid_by_user_id
                """;
    }

    private StoredExpense map(ResultSet rs, int row) throws SQLException {
        return new StoredExpense(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4),
                ExpenseStatus.valueOf(rs.getString(5)), rs.getObject(6, java.time.LocalDate.class),
                rs.getObject(7, java.time.LocalDate.class), rs.getBigDecimal(8), rs.getString(9),
                rs.getObject(10, UUID.class), rs.getString(11), rs.getObject(12, UUID.class), rs.getString(13),
                rs.getTimestamp(14).toInstant(), rs.getLong(15));
    }

    private record IdempotencyRecord(String requestHash, UUID expenseId) {
    }
}
