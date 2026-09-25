package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
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
            return new StoredExpenseCreation(findById(expense.spaceId(), existing.expenseId()), true);
        }

        insert(expense);
        if (expense.payment() != null) recordPayment(expense.id(), expense.spaceId(), actorUserId, expense.payment(), requestedAt);
        jdbc.update("""
                update expense_idempotency_requests
                   set expense_id = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ?
                """, expense.id(), Timestamp.from(expense.createdAt()), expense.spaceId(), actorUserId, OPERATION, key);
        return new StoredExpenseCreation(findById(expense.spaceId(), expense.id()), false);
    }

    @Override
    public StoredExpensePage findBySpace(UUID spaceId, ExpenseListQuery query) {
        var total = jdbc.queryForObject(
                "select count(*) from expense_entries where space_id = ? and status <> 'CANCELLED'", Long.class, spaceId);
        var order = switch (query.sort()) {
            case REFERENCE_DATE -> "e.reference_date";
            case AMOUNT -> "e.charge_amount";
            case DESCRIPTION -> "lower(e.description)";
        };
        var direction = query.direction().name();
        var items = jdbc.query(selectBase() + " where e.space_id = ? and e.status <> 'CANCELLED' order by " + order + " " + direction
                        + ", e.created_at " + direction + ", e.id " + direction + " limit ? offset ?",
                this::map, spaceId, query.size(), (long) query.page() * query.size());
        return new StoredExpensePage(items, total == null ? 0 : total);
    }

    @Override
    public StoredExpense findById(UUID spaceId, UUID expenseId) {
        var results = jdbc.query(selectBase() + " where e.space_id = ? and e.id = ?", this::map, spaceId, expenseId);
        if (results.isEmpty()) throw new com.malyah.accountmanager.expenses.application.ExpenseNotFoundException();
        return results.getFirst();
    }

    @Override
    public java.util.List<com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent> history(
            UUID spaceId, UUID expenseId) {
        var events = new ArrayList<com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent>();
        events.addAll(jdbc.query("""
                select p.event_type, p.actor_user_id, actor.display_name, p.recorded_at, p.reason, p.notes,
                       p.expense_version, p.paid_amount, p.payment_date, p.payer_user_id, payer.display_name
                  from expense_payment_events p
                  join identity_users actor on actor.id=p.actor_user_id
                  join identity_users payer on payer.id=p.payer_user_id
                 where p.space_id=? and p.expense_id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant(),
                    rs.getString(5), rs.getString(6), rs.getLong(7), rs.getBigDecimal(8).toPlainString(),
                    rs.getObject(9, java.time.LocalDate.class), rs.getObject(10, UUID.class), rs.getString(11), null),
                spaceId, expenseId));
        events.addAll(jdbc.query("""
                select c.actor_user_id, actor.display_name, c.corrected_at, c.to_version, c.changed_fields
                  from expense_correction_events c
                  join identity_users actor on actor.id=c.actor_user_id
                 where c.space_id=? and c.expense_id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    "EXPENSE_CORRECTED", rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), null, null, rs.getLong(4), null, null, null, null,
                    rs.getString(5)), spaceId, expenseId));
        events.addAll(jdbc.query("""
                select c.actor_user_id, actor.display_name, c.cancelled_at, c.reason, c.to_version
                  from expense_cancellation_events c
                  join identity_users actor on actor.id=c.actor_user_id
                 where c.space_id=? and c.expense_id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    "EXPENSE_CANCELLED", rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), rs.getString(4), null, rs.getLong(5), null, null, null,
                    null, null), spaceId, expenseId));
        events.sort(java.util.Comparator.comparing(
                com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent::occurredAt)
                .thenComparingLong(com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent::version));
        return java.util.List.copyOf(events);
    }

    @Override
    public StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, Instant at) {
        var hash = hashCorrection(command, corrected);
        int claimed = jdbc.update("""
                insert into expense_idempotency_requests(space_id, actor_user_id, operation, idempotency_key, request_hash, created_at)
                values (?, ?, 'CORRECT_EXPENSE', ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, command.idempotencyKey(), hash, Timestamp.from(at));
        if (claimed == 0) {
            var previous = jdbc.queryForObject("""
                    select request_hash, expense_id from expense_idempotency_requests
                    where space_id=? and actor_user_id=? and operation='CORRECT_EXPENSE' and idempotency_key=? for update
                    """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                    spaceId, actorId, command.idempotencyKey());
            if (previous == null || previous.expenseId() == null || !hash.equals(previous.requestHash()))
                throw new ExpenseIdempotencyConflictException();
            return new StoredExpenseCreation(findById(spaceId, previous.expenseId()), true);
        }

        var locked = jdbc.query("select id from expense_entries where id=? and space_id=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), command.expenseId(), spaceId);
        if (locked.isEmpty()) throw new com.malyah.accountmanager.expenses.application.ExpenseNotFoundException();
        var current = findById(spaceId, command.expenseId());
        if (!com.malyah.accountmanager.expenses.domain.CorrectionEligibility.eligible(
                current.status(), command.status(), current.version(), command.version()))
            throw new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException();
        var changedFields = changedFields(current, corrected);
        if (changedFields.isEmpty())
            throw new com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException(
                    "correction", "Informe ao menos uma alteração.");

        var payment = corrected.payment();
        jdbc.update("""
                update expense_entries set description=?, charge_amount=?, due_date=?, reference_date=?, notes=?,
                    paid_amount=?, payment_date=?, paid_by_user_id=?, payment_notes=?, version=version+1
                where id=? and space_id=? and version=? and status=?
                """, corrected.description(), corrected.amount().value(), corrected.dueDate(), corrected.referenceDate(),
                corrected.notes(), payment == null ? null : payment.amount().value(), corrected.paymentDate(),
                payment == null ? null : payment.payerId(), payment == null ? null : payment.notes(),
                command.expenseId(), spaceId, command.version(), command.status().name());
        recordCorrection(current, corrected, actorId, at, String.join(",", changedFields));
        jdbc.update("""
                update expense_idempotency_requests set expense_id=?, completed_at=?
                where space_id=? and actor_user_id=? and operation='CORRECT_EXPENSE' and idempotency_key=?
                """, command.expenseId(), Timestamp.from(at), spaceId, actorId, command.idempotencyKey());
        return new StoredExpenseCreation(findById(spaceId, command.expenseId()), false);
    }

    @Override
    public StoredExpenseCreation settle(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at) {
        // The record contains fixed-format amount/date/UUID fields followed by the free-text notes.
        String payload = command.expenseId() + ":" + command.version() + ":" + payment.canonical();
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        int claimed = jdbc.update("""
                insert into expense_idempotency_requests(space_id, actor_user_id, operation, idempotency_key, request_hash, created_at)
                values (?, ?, 'SETTLE_EXPENSE', ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, command.idempotencyKey(), hash, Timestamp.from(at));
        if (claimed == 0) {
            var previous = jdbc.queryForObject("""
                    select request_hash, expense_id from expense_idempotency_requests
                    where space_id=? and actor_user_id=? and operation='SETTLE_EXPENSE' and idempotency_key=? for update
                    """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                    spaceId, actorId, command.idempotencyKey());
            if (previous == null || !hash.equals(previous.requestHash())) throw new ExpenseIdempotencyConflictException();
            return new StoredExpenseCreation(findById(spaceId, previous.expenseId()), true);
        }
        var locked = jdbc.query("select id from expense_entries where id=? and space_id=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), command.expenseId(), spaceId);
        if (locked.isEmpty()) throw new com.malyah.accountmanager.expenses.application.ExpenseNotFoundException();
        var current = findById(spaceId, command.expenseId());
        if (!com.malyah.accountmanager.expenses.domain.PaymentEligibility.eligible(current.status(), current.version(), command.version()))
            throw new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException();
        jdbc.update("""
                update expense_entries set status='PAID', paid_amount=?, payment_date=?, paid_by_user_id=?,
                    payment_recorded_by_user_id=?, payment_notes=?, payment_recorded_at=?, version=version+1
                where id=? and space_id=?
                """, payment.amount().value(), payment.date(), payment.payerId(), actorId, payment.notes(),
                Timestamp.from(at), command.expenseId(), spaceId);
        recordPayment(command.expenseId(), spaceId, actorId, payment, at);
        jdbc.update("""
                update expense_idempotency_requests set expense_id=?, completed_at=?
                where space_id=? and actor_user_id=? and operation='SETTLE_EXPENSE' and idempotency_key=?
                """, command.expenseId(), Timestamp.from(at), spaceId, actorId, command.idempotencyKey());
        return new StoredExpenseCreation(findById(spaceId, command.expenseId()), false);
    }

    @Override
    public StoredExpenseCreation reversePayment(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.ReversePaymentCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at) {
        var hash = hashAction(command.expenseId(), command.version(), reason.value());
        if (!claimAction(spaceId, actorId, "REVERSE_PAYMENT", command.idempotencyKey(), hash, at))
            return new StoredExpenseCreation(findById(spaceId, command.expenseId()), true);
        lock(spaceId, command.expenseId());
        var current = findById(spaceId, command.expenseId());
        if (!com.malyah.accountmanager.expenses.domain.PaymentReversalEligibility.eligible(
                current.status(), current.version(), command.version()))
            throw new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_payment_events(id, expense_id, space_id, event_type, charge_amount, paid_amount,
                    payment_date, payer_user_id, actor_user_id, notes, recorded_at, expense_version, reason)
                select ?, id, space_id, 'PAYMENT_REVERSED', charge_amount, paid_amount, payment_date,
                    paid_by_user_id, ?, payment_notes, ?, version+1, ?
                  from expense_entries where id=? and space_id=?
                """, UUID.randomUUID(), actorId, Timestamp.from(at), reason.value(), command.expenseId(), spaceId);
        jdbc.update("""
                update expense_entries set status='PENDING', paid_amount=null, payment_date=null,
                    paid_by_user_id=null, payment_recorded_by_user_id=null, payment_notes=null,
                    payment_recorded_at=null, reference_date=due_date, version=version+1
                where id=? and space_id=? and version=? and status='PAID'
                """, command.expenseId(), spaceId, command.version());
        completeAction(spaceId, actorId, "REVERSE_PAYMENT", command.idempotencyKey(), command.expenseId(), at);
        return new StoredExpenseCreation(findById(spaceId, command.expenseId()), false);
    }

    @Override
    public StoredExpenseCreation cancel(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CancelExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at) {
        var hash = hashAction(command.expenseId(), command.version(), reason.value());
        if (!claimAction(spaceId, actorId, "CANCEL_EXPENSE", command.idempotencyKey(), hash, at))
            return new StoredExpenseCreation(findById(spaceId, command.expenseId()), true);
        lock(spaceId, command.expenseId());
        var current = findById(spaceId, command.expenseId());
        if (!com.malyah.accountmanager.expenses.domain.CancellationEligibility.eligible(
                current.status(), current.version(), command.version()))
            throw new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException();
        jdbc.update("""
                insert into expense_cancellation_events(
                    id, expense_id, space_id, actor_user_id, reason, cancelled_at, from_version, to_version)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), command.expenseId(), spaceId, actorId, reason.value(), Timestamp.from(at),
                command.version(), command.version() + 1);
        jdbc.update("""
                update expense_entries set status='CANCELLED', cancelled_at=?, cancelled_by_user_id=?,
                    cancellation_reason=?, version=version+1
                where id=? and space_id=? and version=? and status='PENDING'
                """, Timestamp.from(at), actorId, reason.value(), command.expenseId(), spaceId, command.version());
        completeAction(spaceId, actorId, "CANCEL_EXPENSE", command.idempotencyKey(), command.expenseId(), at);
        return new StoredExpenseCreation(findById(spaceId, command.expenseId()), false);
    }

    private boolean claimAction(UUID spaceId, UUID actorId, String operation, UUID key, String hash, Instant at) {
        var claimed = jdbc.update("""
                insert into expense_idempotency_requests(
                    space_id, actor_user_id, operation, idempotency_key, request_hash, created_at)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, operation, key, hash, Timestamp.from(at));
        if (claimed == 1) return true;
        var previous = jdbc.queryForObject("""
                select request_hash, expense_id from expense_idempotency_requests
                where space_id=? and actor_user_id=? and operation=? and idempotency_key=? for update
                """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                spaceId, actorId, operation, key);
        if (previous == null || previous.expenseId() == null || !hash.equals(previous.requestHash()))
            throw new ExpenseIdempotencyConflictException();
        return false;
    }

    private void completeAction(UUID spaceId, UUID actorId, String operation, UUID key, UUID expenseId, Instant at) {
        jdbc.update("""
                update expense_idempotency_requests set expense_id=?, completed_at=?
                where space_id=? and actor_user_id=? and operation=? and idempotency_key=?
                """, expenseId, Timestamp.from(at), spaceId, actorId, operation, key);
    }

    private void lock(UUID spaceId, UUID expenseId) {
        var locked = jdbc.query("select id from expense_entries where id=? and space_id=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), expenseId, spaceId);
        if (locked.isEmpty()) throw new com.malyah.accountmanager.expenses.application.ExpenseNotFoundException();
    }

    private String hashAction(UUID expenseId, long version, String reason) {
        var canonical = expenseId + ":" + version + ":" + encoded(reason);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void recordPayment(UUID expenseId, UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at) {
        jdbc.update("""
                update expense_entries set payment_notes=?, payment_recorded_at=? where id=?
                """, payment.notes(), Timestamp.from(at), expenseId);
        jdbc.update("""
                insert into expense_payment_events(id, expense_id, space_id, event_type, charge_amount, paid_amount,
                    payment_date, payer_user_id, actor_user_id, notes, recorded_at, expense_version)
                select ?, id, space_id, 'EXPENSE_PAID', charge_amount, paid_amount, payment_date,
                    paid_by_user_id, ?, ?, ?, version from expense_entries where id=? and space_id=?
                """, UUID.randomUUID(), actorId, payment.notes(), Timestamp.from(at), expenseId, spaceId);
    }

    private void insert(OneOffExpense expense) {
        var paid = expense.status() == ExpenseStatus.PAID;
        jdbc.update("""
                insert into expense_entries(
                    id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, notes, paid_amount, payment_date, paid_by_user_id,
                    payment_recorded_by_user_id, payment_recorded_at, payment_notes,
                    created_by_user_id, created_at, version
                ) values (?, ?, 'ONE_OFF', ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, expense.id(), expense.spaceId(), expense.description(), expense.amount().value(),
                expense.status().name(), expense.dueDate(), expense.referenceDate(), expense.notes(),
                paid ? expense.payment().amount().value() : null, expense.paymentDate(),
                paid ? expense.payment().payerId() : null, paid ? expense.createdByUserId() : null,
                paid ? Timestamp.from(expense.createdAt()) : null, paid ? expense.payment().notes() : null,
                expense.createdByUserId(), Timestamp.from(expense.createdAt()));
    }

    private String hashCorrection(
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command, OneOffExpense corrected) {
        var payment = corrected.payment();
        var canonical = command.expenseId() + ":" + command.version() + ":" + command.status() + ":"
                + encoded(corrected.description()) + corrected.amount().canonical() + ":"
                + corrected.dueDate() + ":" + encoded(corrected.notes())
                + (payment == null ? "NO_PAYMENT" : payment.canonical());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private String encoded(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private java.util.List<String> changedFields(StoredExpense current, OneOffExpense corrected) {
        var changed = new ArrayList<String>();
        if (!current.description().equals(corrected.description())) changed.add("description");
        if (current.amount().compareTo(corrected.amount().value()) != 0) changed.add("amount");
        if (!Objects.equals(current.dueDate(), corrected.dueDate())) changed.add("dueDate");
        if (!Objects.equals(current.notes(), corrected.notes())) changed.add("notes");
        if (corrected.payment() != null) {
            if (current.paidAmount().compareTo(corrected.payment().amount().value()) != 0) changed.add("paidAmount");
            if (!Objects.equals(current.paymentDate(), corrected.payment().date())) changed.add("paymentDate");
            if (!Objects.equals(current.paidByUserId(), corrected.payment().payerId())) changed.add("paidByUserId");
            if (!Objects.equals(current.paymentAudit() == null ? null : current.paymentAudit().notes(),
                    corrected.payment().notes())) changed.add("paymentNotes");
        }
        return changed;
    }

    private void recordCorrection(StoredExpense current, OneOffExpense corrected, UUID actorId, Instant at,
            String changedFields) {
        var payment = corrected.payment();
        jdbc.update("""
                insert into expense_correction_events(
                    id, expense_id, space_id, actor_user_id, corrected_at, from_version, to_version, changed_fields,
                    old_description, new_description, old_charge_amount, new_charge_amount,
                    old_due_date, new_due_date, old_notes, new_notes,
                    old_paid_amount, new_paid_amount, old_payment_date, new_payment_date,
                    old_payer_user_id, new_payer_user_id, old_payment_notes, new_payment_notes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), current.id(), current.spaceId(), actorId, Timestamp.from(at),
                current.version(), current.version() + 1, changedFields,
                current.description(), corrected.description(), current.amount(), corrected.amount().value(),
                current.dueDate(), corrected.dueDate(), current.notes(), corrected.notes(),
                current.paidAmount(), payment == null ? null : payment.amount().value(),
                current.paymentDate(), payment == null ? null : payment.date(),
                current.paidByUserId(), payment == null ? null : payment.payerId(),
                current.paymentAudit() == null ? null : current.paymentAudit().notes(),
                payment == null ? null : payment.notes());
    }

    private String selectBase() {
        return """
                select e.id, e.space_id, e.description, e.charge_amount, e.status, e.due_date,
                       e.payment_date, e.paid_amount, e.notes, e.created_by_user_id,
                       creator.display_name, e.paid_by_user_id, payer.display_name, e.created_at, e.version,
                       e.payment_recorded_by_user_id, recorder.display_name, e.payment_recorded_at, e.payment_notes
                  from expense_entries e
                  join identity_users creator on creator.id = e.created_by_user_id
                  left join identity_users payer on payer.id = e.paid_by_user_id
                  left join identity_users recorder on recorder.id = e.payment_recorded_by_user_id
                """;
    }

    private StoredExpense map(ResultSet rs, int row) throws SQLException {
        return new StoredExpense(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4),
                ExpenseStatus.valueOf(rs.getString(5)), rs.getObject(6, java.time.LocalDate.class),
                rs.getObject(7, java.time.LocalDate.class), rs.getBigDecimal(8), rs.getString(9),
                rs.getObject(10, UUID.class), rs.getString(11), rs.getObject(12, UUID.class), rs.getString(13),
                rs.getTimestamp(14).toInstant(), rs.getLong(15), rs.getObject(16) == null ? null :
                        new com.malyah.accountmanager.expenses.application.PaymentAudit(rs.getObject(16, UUID.class),
                                rs.getString(17), rs.getTimestamp(18).toInstant(), rs.getString(19)));
    }

    private record IdempotencyRecord(String requestHash, UUID expenseId) {
    }
}
