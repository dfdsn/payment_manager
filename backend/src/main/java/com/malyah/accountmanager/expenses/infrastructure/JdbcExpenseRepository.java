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
            OneOffExpense expense, UUID actorUserId, UUID key, String requestHash, UUID categoryId,
            UUID responsibleUserId, Instant requestedAt) {
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

        insert(expense, categoryId, responsibleUserId);
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
        var where = new StringBuilder(" where e.space_id = ?");
        var parameters = new java.util.ArrayList<Object>(); parameters.add(spaceId);
        if (query.search()!=null && !query.search().isBlank()) { where.append(" and lower(e.description) like lower(?) escape '!'"); parameters.add("%"+query.search().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        var dateColumn = query.dateBasis()==com.malyah.accountmanager.expenses.application.ExpenseDateBasis.PAYMENT_DATE ? "e.payment_date" : "e.reference_date";
        if(query.dateFrom()!=null){where.append(" and ").append(dateColumn).append(" >= ?");parameters.add(query.dateFrom());}
        if(query.dateTo()!=null){where.append(" and ").append(dateColumn).append(" <= ?");parameters.add(query.dateTo());}
        if(query.withoutCategory())where.append(" and e.category_id is null"); else if(query.categoryId()!=null){where.append(" and e.category_id=?");parameters.add(query.categoryId());}
        if(query.withoutResponsible())where.append(" and e.responsible_user_id is null"); else if(query.responsibleUserId()!=null){where.append(" and e.responsible_user_id=?");parameters.add(query.responsibleUserId());}
        if(query.payerUserId()!=null){where.append(" and e.paid_by_user_id=?");parameters.add(query.payerUserId());}
        switch(query.status()) {
            case ACTIVE -> where.append(" and e.status <> 'CANCELLED'");
            case PENDING -> where.append(" and e.status='PENDING'");
            case OVERDUE -> {where.append(" and e.status='PENDING' and e.due_date < ?");parameters.add(query.today());}
            case PAID -> where.append(" and e.status='PAID'");
            case CANCELLED -> where.append(" and e.status='CANCELLED'");
            case ALL -> { }
        }
        var total = jdbc.queryForObject("select count(*) from expense_entries e"+where, Long.class, parameters.toArray());
        var order = switch (query.sort()) {
            case REFERENCE_DATE -> "e.reference_date";
            case AMOUNT -> "e.charge_amount";
            case DESCRIPTION -> "lower(e.description)";
        };
        var direction = query.direction().name();
        var itemParameters=new java.util.ArrayList<>(parameters);itemParameters.add(query.size());itemParameters.add((long)query.page()*query.size());
        var items = jdbc.query(selectBase() + where + " order by " + order + " " + direction
                        + ", e.created_at " + direction + ", e.id " + direction + " limit ? offset ?",
                this::map, itemParameters.toArray());
        return new StoredExpensePage(items, total == null ? 0 : total);
    }

    @Override
    public com.malyah.accountmanager.expenses.application.ExpenseFilterOptions filterOptions(UUID spaceId) {
        var responsible = filterPeople(spaceId, "e.responsible_user_id");
        var payers = filterPeople(spaceId, "e.paid_by_user_id");
        return new com.malyah.accountmanager.expenses.application.ExpenseFilterOptions(responsible, payers);
    }

    private java.util.List<com.malyah.accountmanager.expenses.application.ExpenseFilterPerson> filterPeople(
            UUID spaceId, String column) {
        return jdbc.query("""
                select distinct u.id, u.display_name,
                       exists(select 1 from space_memberships m
                               where m.space_id=e.space_id and m.user_id=u.id and m.active=true) active_member
                  from expense_entries e
                  join identity_users u on u.id = %s
                 where e.space_id=? and %s is not null
                 order by u.display_name, u.id
                """.formatted(column, column), (rs, row) ->
                new com.malyah.accountmanager.expenses.application.ExpenseFilterPerson(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)), spaceId);
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
                select e.created_by_user_id, actor.display_name, e.created_at
                  from expense_entries e join identity_users actor on actor.id=e.created_by_user_id
                 where e.space_id=? and e.id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    "EXPENSE_CREATED", rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), null, null, 0, null, null, null, null, null, null,
                    java.util.List.of()), spaceId, expenseId));
        events.addAll(jdbc.query("""
                select p.event_type, p.actor_user_id, actor.display_name, p.recorded_at, p.reason, p.notes,
                       p.expense_version, p.paid_amount, p.payment_date, p.payer_user_id, payer.display_name,
                       p.batch_operation_id
                  from expense_payment_events p
                  join identity_users actor on actor.id=p.actor_user_id
                  join identity_users payer on payer.id=p.payer_user_id
                 where p.space_id=? and p.expense_id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant(),
                    rs.getString(5), rs.getString(6), rs.getLong(7), rs.getBigDecimal(8).toPlainString(),
                    rs.getObject(9, java.time.LocalDate.class), rs.getObject(10, UUID.class), rs.getString(11), null,
                    rs.getObject(12, UUID.class)),
                spaceId, expenseId));
        events.addAll(jdbc.query("""
                select c.actor_user_id, actor.display_name, c.corrected_at, c.to_version, c.changed_fields,
                       c.old_description, c.new_description, c.old_charge_amount, c.new_charge_amount,
                       c.old_due_date, c.new_due_date, c.old_notes, c.new_notes,
                       c.old_paid_amount, c.new_paid_amount, c.old_payment_date, c.new_payment_date,
                       old_payer.display_name, new_payer.display_name,
                       c.old_category_name, c.new_category_name,
                       c.old_responsible_name, c.new_responsible_name
                  from expense_correction_events c
                  join identity_users actor on actor.id=c.actor_user_id
                  left join identity_users old_payer on old_payer.id=c.old_payer_user_id
                  left join identity_users new_payer on new_payer.id=c.new_payer_user_id
                 where c.space_id=? and c.expense_id=?
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent(
                    "EXPENSE_CORRECTED", rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), null, null, rs.getLong(4), null, null, null, null,
                    rs.getString(5), null, correctionChanges(rs)), spaceId, expenseId));
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
                .thenComparingLong(com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent::version)
                .thenComparing(com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent::type));
        return java.util.List.copyOf(events);
    }

    private java.util.List<com.malyah.accountmanager.expenses.application.ExpenseFieldChange> correctionChanges(
            ResultSet rs) throws SQLException {
        var changed = java.util.Set.of(rs.getString(5).split(","));
        var result = new ArrayList<com.malyah.accountmanager.expenses.application.ExpenseFieldChange>();
        addChange(result, changed, "description", rs.getObject(6), rs.getObject(7));
        addChange(result, changed, "amount", rs.getObject(8), rs.getObject(9));
        addChange(result, changed, "dueDate", rs.getObject(10), rs.getObject(11));
        addChange(result, changed, "notes", rs.getObject(12), rs.getObject(13));
        addChange(result, changed, "paidAmount", rs.getObject(14), rs.getObject(15));
        addChange(result, changed, "paymentDate", rs.getObject(16), rs.getObject(17));
        addChange(result, changed, "paidByUserId", rs.getObject(18), rs.getObject(19));
        addChange(result, changed, "categoryId", rs.getObject(20), rs.getObject(21));
        addChange(result, changed, "responsibleUserId", rs.getObject(22), rs.getObject(23));
        return java.util.List.copyOf(result);
    }

    private void addChange(java.util.List<com.malyah.accountmanager.expenses.application.ExpenseFieldChange> target,
            java.util.Set<String> changed, String field, Object previous, Object current) {
        if (changed.contains(field)) target.add(new com.malyah.accountmanager.expenses.application.ExpenseFieldChange(
                field, previous == null ? null : previous.toString(), current == null ? null : current.toString()));
    }

    @Override
    public StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, UUID categoryId, UUID responsibleUserId, Instant at) {
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
        if (!Objects.equals(current.categoryId(), categoryId)) changedFields.add("categoryId");
        if (!Objects.equals(current.responsibleUserId(), responsibleUserId)) changedFields.add("responsibleUserId");
        if (changedFields.isEmpty())
            throw new com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException(
                    "correction", "Informe ao menos uma alteração.");

        var payment = corrected.payment();
        jdbc.update("""
                update expense_entries set description=?, charge_amount=?, due_date=?, reference_date=?, notes=?, category_id=?, responsible_user_id=?,
                    paid_amount=?, payment_date=?, paid_by_user_id=?, payment_notes=?, version=version+1
                where id=? and space_id=? and version=? and status=?
                """, corrected.description(), corrected.amount().value(), corrected.dueDate(), corrected.referenceDate(),
                corrected.notes(), categoryId, responsibleUserId, payment == null ? null : payment.amount().value(), corrected.paymentDate(),
                payment == null ? null : payment.payerId(), payment == null ? null : payment.notes(),
                command.expenseId(), spaceId, command.version(), command.status().name());
        recordCorrection(current, corrected, categoryId, responsibleUserId, actorId, at, String.join(",", changedFields));
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
    public com.malyah.accountmanager.expenses.application.BatchSettlementResult settleBatch(
            UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.BatchSettlementCommand command,
            com.malyah.accountmanager.expenses.domain.BatchPaymentInstruction instruction, Instant at) {
        var orderedItems = command.items().stream()
                .sorted(java.util.Comparator.comparing(item -> item.expenseId().toString())).toList();
        var hash = hashBatch(orderedItems, instruction);
        var operationId = UUID.randomUUID();
        var claimed = jdbc.update("""
                insert into expense_batch_payment_operations(
                    id, space_id, actor_user_id, payer_user_id, payment_date,
                    idempotency_key, request_hash, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                """, operationId, spaceId, actorId, instruction.payerId(), instruction.date(),
                command.idempotencyKey(), hash, Timestamp.from(at));
        if (claimed == 0) {
            var previous = jdbc.queryForObject("""
                    select id, request_hash, completed_at from expense_batch_payment_operations
                    where space_id=? and actor_user_id=? and idempotency_key=? for update
                    """, (rs, row) -> new BatchOperationRecord(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
                    spaceId, actorId, command.idempotencyKey());
            if (previous == null || previous.completedAt() == null || !hash.equals(previous.requestHash()))
                throw new ExpenseIdempotencyConflictException();
            return new com.malyah.accountmanager.expenses.application.BatchSettlementResult(
                    previous.id(), batchResults(previous.id()), true);
        }

        var placeholders = String.join(",", java.util.Collections.nCopies(orderedItems.size(), "?"));
        var arguments = new ArrayList<Object>();
        arguments.add(spaceId);
        orderedItems.forEach(item -> arguments.add(item.expenseId()));
        var locked = jdbc.query("""
                select id, charge_amount, status, version, charge_confirmed
                  from expense_entries
                 where space_id=? and id in (%s)
                 order by id for update
                """.formatted(placeholders), (rs, row) -> new BatchLockedExpense(
                    rs.getObject(1, UUID.class), rs.getBigDecimal(2), ExpenseStatus.valueOf(rs.getString(3)),
                    rs.getLong(4), rs.getBoolean(5)), arguments.toArray());
        var byId = locked.stream().collect(java.util.stream.Collectors.toMap(BatchLockedExpense::id, item -> item));
        var problems = new ArrayList<com.malyah.accountmanager.expenses.application.BatchSettlementItemProblem>();
        for (var item : orderedItems) {
            var expense = byId.get(item.expenseId());
            if (expense == null) {
                problems.add(batchProblem(item.expenseId(), "UNAVAILABLE",
                        "O lançamento não está disponível neste espaço."));
            } else {
                var eligibility = com.malyah.accountmanager.expenses.domain.BatchPaymentEligibility.evaluate(
                        expense.chargeConfirmed(), expense.status(), expense.version(), item.version());
                switch (eligibility) {
                    case AMOUNT_UNCONFIRMED -> problems.add(batchProblem(item.expenseId(), eligibility.name(),
                            "Confirme o valor do lançamento antes de quitá-lo."));
                    case STATE_INCOMPATIBLE -> problems.add(batchProblem(item.expenseId(), eligibility.name(),
                            "O lançamento não está pendente."));
                    case VERSION_CONFLICT -> problems.add(batchProblem(item.expenseId(), eligibility.name(),
                            "O lançamento foi alterado depois da seleção."));
                    case ELIGIBLE -> { }
                }
            }
        }
        if (!problems.isEmpty())
            throw new com.malyah.accountmanager.expenses.application.BatchSettlementConflictException(problems);

        for (var item : orderedItems) {
            var current = byId.get(item.expenseId());
            var payment = instruction.paymentFor(current.amount());
            var changed = jdbc.update("""
                    update expense_entries set status='PAID', paid_amount=?, payment_date=?, paid_by_user_id=?,
                        payment_recorded_by_user_id=?, payment_notes=null, payment_recorded_at=?, version=version+1
                    where id=? and space_id=? and version=? and status='PENDING' and charge_confirmed=true
                    """, payment.amount().value(), payment.date(), payment.payerId(), actorId, Timestamp.from(at),
                    item.expenseId(), spaceId, item.version());
            if (changed != 1)
                throw new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException();
            recordPayment(item.expenseId(), spaceId, actorId, payment, at, operationId);
            jdbc.update("""
                    insert into expense_batch_payment_items(
                        batch_operation_id, expense_id, from_version, to_version, paid_amount)
                    values (?, ?, ?, ?, ?)
                    """, operationId, item.expenseId(), item.version(), item.version() + 1, payment.amount().value());
        }
        jdbc.update("update expense_batch_payment_operations set completed_at=? where id=?",
                Timestamp.from(at), operationId);
        return new com.malyah.accountmanager.expenses.application.BatchSettlementResult(
                operationId, batchResults(operationId), false);
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
        recordPayment(expenseId, spaceId, actorId, payment, at, null);
    }

    private void recordPayment(UUID expenseId, UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at, UUID batchOperationId) {
        jdbc.update("""
                update expense_entries set payment_notes=?, payment_recorded_at=? where id=?
                """, payment.notes(), Timestamp.from(at), expenseId);
        jdbc.update("""
                insert into expense_payment_events(id, expense_id, space_id, event_type, charge_amount, paid_amount,
                    payment_date, payer_user_id, actor_user_id, notes, recorded_at, expense_version,
                    batch_operation_id)
                select ?, id, space_id, 'EXPENSE_PAID', charge_amount, paid_amount, payment_date,
                    paid_by_user_id, ?, ?, ?, version, ? from expense_entries where id=? and space_id=?
                """, UUID.randomUUID(), actorId, payment.notes(), Timestamp.from(at), batchOperationId,
                expenseId, spaceId);
    }

    private java.util.List<com.malyah.accountmanager.expenses.application.BatchSettlementItemResult> batchResults(
            UUID operationId) {
        return jdbc.query("""
                select expense_id, from_version, to_version, paid_amount
                  from expense_batch_payment_items where batch_operation_id=? order by expense_id
                """, (rs, row) -> new com.malyah.accountmanager.expenses.application.BatchSettlementItemResult(
                    rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3),
                    rs.getBigDecimal(4).toPlainString()), operationId);
    }

    private com.malyah.accountmanager.expenses.application.BatchSettlementItemProblem batchProblem(
            UUID expenseId, String code, String message) {
        return new com.malyah.accountmanager.expenses.application.BatchSettlementItemProblem(expenseId, code, message);
    }

    private String hashBatch(
            java.util.List<com.malyah.accountmanager.expenses.application.BatchSettlementItem> items,
            com.malyah.accountmanager.expenses.domain.BatchPaymentInstruction instruction) {
        var canonical = new StringBuilder(instruction.date().toString()).append(':').append(instruction.payerId());
        items.forEach(item -> canonical.append(':').append(item.expenseId()).append('@').append(item.version()));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void insert(OneOffExpense expense, UUID categoryId, UUID responsibleUserId) {
        var paid = expense.status() == ExpenseStatus.PAID;
        jdbc.update("""
                insert into expense_entries(
                    id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, notes, paid_amount, payment_date, paid_by_user_id,
                    payment_recorded_by_user_id, payment_recorded_at, payment_notes,
                    created_by_user_id, created_at, version, category_id, responsible_user_id
                ) values (?, ?, 'ONE_OFF', ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, expense.id(), expense.spaceId(), expense.description(), expense.amount().value(),
                expense.status().name(), expense.dueDate(), expense.referenceDate(), expense.notes(),
                paid ? expense.payment().amount().value() : null, expense.paymentDate(),
                paid ? expense.payment().payerId() : null, paid ? expense.createdByUserId() : null,
                paid ? Timestamp.from(expense.createdAt()) : null, paid ? expense.payment().notes() : null,
                expense.createdByUserId(), Timestamp.from(expense.createdAt()), categoryId, responsibleUserId);
    }

    private String hashCorrection(
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command, OneOffExpense corrected) {
        var payment = corrected.payment();
        var canonical = command.expenseId() + ":" + command.version() + ":" + command.status() + ":"
                + encoded(corrected.description()) + corrected.amount().canonical() + ":"
                + corrected.dueDate() + ":" + encoded(corrected.notes())
                + (payment == null ? "NO_PAYMENT" : payment.canonical()) + ":" + command.categoryId()
                + ":" + command.responsibleUserId();
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

    private void recordCorrection(StoredExpense current, OneOffExpense corrected, UUID categoryId,
            UUID responsibleUserId, UUID actorId, Instant at,
            String changedFields) {
        var payment = corrected.payment();
        jdbc.update("""
                insert into expense_correction_events(
                    id, expense_id, space_id, actor_user_id, corrected_at, from_version, to_version, changed_fields,
                    old_description, new_description, old_charge_amount, new_charge_amount,
                    old_due_date, new_due_date, old_notes, new_notes,
                    old_paid_amount, new_paid_amount, old_payment_date, new_payment_date,
                    old_payer_user_id, new_payer_user_id, old_payment_notes, new_payment_notes,
                    old_category_id, new_category_id, old_category_name, new_category_name,
                    old_responsible_user_id, new_responsible_user_id, old_responsible_name, new_responsible_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    (select name from expense_categories where id=?), ?, ?, ?,
                    (select display_name from identity_users where id=?))
                """, UUID.randomUUID(), current.id(), current.spaceId(), actorId, Timestamp.from(at),
                current.version(), current.version() + 1, changedFields,
                current.description(), corrected.description(), current.amount(), corrected.amount().value(),
                current.dueDate(), corrected.dueDate(), current.notes(), corrected.notes(),
                current.paidAmount(), payment == null ? null : payment.amount().value(),
                current.paymentDate(), payment == null ? null : payment.date(),
                current.paidByUserId(), payment == null ? null : payment.payerId(),
                current.paymentAudit() == null ? null : current.paymentAudit().notes(),
                payment == null ? null : payment.notes(), current.categoryId(), categoryId,
                current.categoryName(), categoryId, current.responsibleUserId(), responsibleUserId,
                current.responsibleDisplayName(), responsibleUserId);
    }

    private String selectBase() {
        return """
                select e.id, e.space_id, e.description, e.charge_amount, e.status, e.due_date,
                       e.payment_date, e.paid_amount, e.notes, e.created_by_user_id,
                       creator.display_name, e.paid_by_user_id, payer.display_name, e.created_at, e.version,
                       e.payment_recorded_by_user_id, recorder.display_name, e.payment_recorded_at, e.payment_notes,
                       e.category_id, category.name, e.responsible_user_id, responsible.display_name,
                       e.origin, e.charge_confirmed
                  from expense_entries e
                  join identity_users creator on creator.id = e.created_by_user_id
                  left join identity_users payer on payer.id = e.paid_by_user_id
                  left join identity_users recorder on recorder.id = e.payment_recorded_by_user_id
                  left join expense_categories category on category.id = e.category_id and category.space_id = e.space_id
                  left join identity_users responsible on responsible.id = e.responsible_user_id
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
                                rs.getString(17), rs.getTimestamp(18).toInstant(), rs.getString(19)),
                rs.getObject(20, UUID.class), rs.getString(21), rs.getObject(22, UUID.class), rs.getString(23),
                rs.getString(24), rs.getBoolean(25));
    }

    private record IdempotencyRecord(String requestHash, UUID expenseId) {
    }

    private record BatchOperationRecord(UUID id, String requestHash, Instant completedAt) { }

    private record BatchLockedExpense(
            UUID id, java.math.BigDecimal amount, ExpenseStatus status, long version, boolean chargeConfirmed) { }
}
