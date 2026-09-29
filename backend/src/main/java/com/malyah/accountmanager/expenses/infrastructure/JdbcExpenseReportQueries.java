package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PaymentRecord;
import com.malyah.accountmanager.expenses.application.PaymentRecordPage;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * E06 aggregation over the whole authorized selection, never over a loaded page. PostgreSQL sums
 * {@code NUMERIC(10,2)} into unbounded {@code NUMERIC}, so totals keep exact cents above a single charge limit.
 */
public final class JdbcExpenseReportQueries implements ExpenseReportQueries {
    private final JdbcTemplate jdbc;

    public JdbcExpenseReportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExpenseTotalsBucket> totals(UUID spaceId, ExpenseSelection selection) {
        Objects.requireNonNull(selection.today(), "today");
        var predicate = ExpenseSelectionPredicate.of(spaceId, selection);
        var parameters = new ArrayList<Object>();
        parameters.add(selection.today());
        parameters.addAll(predicate.parameters());
        return jdbc.query("""
                select e.status, e.charge_confirmed, (e.status = 'PENDING' and e.due_date < ?) as overdue,
                       count(*),
                       coalesce(sum(e.charge_amount), 0),
                       coalesce(sum(e.paid_amount), 0),
                       coalesce(sum(greatest(e.paid_amount - e.charge_amount, 0)), 0),
                       coalesce(sum(greatest(e.charge_amount - e.paid_amount, 0)), 0)
                  from expense_entries e
                """ + predicate.where() + " and e.status <> 'CANCELLED' group by 1, 2, 3 order by 1, 2, 3",
                (rs, row) -> new ExpenseTotalsBucket(ExpenseStatus.valueOf(rs.getString(1)), rs.getBoolean(2),
                rs.getBoolean(3), rs.getLong(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8)), parameters.toArray());
    }

    @Override
    public PaymentRecordPage payments(UUID spaceId, ExpenseSelection selection, int page, int size, PaymentSort sort,
            SortDirection direction) {
        if (selection.dateBasis() != ExpenseDateBasis.PAYMENT_DATE || selection.status() != ExpenseStatusFilter.PAID)
            throw new IllegalArgumentException("The payment view selects active payments by payment date.");
        if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page.");
        var predicate = ExpenseSelectionPredicate.of(spaceId, selection);
        var total = jdbc.queryForObject("select count(*) from expense_entries e" + predicate.where(), Long.class,
                predicate.parameters().toArray());
        var order = switch (sort) {
            case PAYMENT_DATE -> "e.payment_date";
            case PAID_AMOUNT -> "e.paid_amount";
            case DESCRIPTION -> "lower(e.description)";
        };
        var dir = direction.name();
        var parameters = new ArrayList<Object>();
        parameters.add(PAYMENT_FIELDS_PATTERN);
        parameters.add(PAYMENT_FIELDS_PATTERN);
        parameters.addAll(predicate.parameters());
        parameters.add(size);
        parameters.add((long) page * size);
        // The active payment is the latest EXPENSE_PAID event: a reversal clears the payment of the entry and a new
        // payment records a new event, so earlier payments and their corrections never reach this view.
        var content = jdbc.query("""
                select e.id, e.description, e.origin, e.installment_purchase_id, e.installment_number,
                       e.installment_count, e.due_date, e.charge_amount, e.charge_confirmed, e.paid_amount,
                       e.payment_date, e.paid_by_user_id, payer.display_name, e.payment_recorded_by_user_id,
                       recorder.display_name, e.payment_recorded_at, active.batch_operation_id is not null,
                       category.name, responsible.display_name, corrections.count, last_correction.actor_user_id,
                       actor.display_name, last_correction.corrected_at, last_correction.changed_fields
                  from expense_entries e
                  join identity_users payer on payer.id = e.paid_by_user_id
                  join identity_users recorder on recorder.id = e.payment_recorded_by_user_id
                  left join expense_categories category on category.id = e.category_id and category.space_id = e.space_id
                  left join identity_users responsible on responsible.id = e.responsible_user_id
                  left join lateral (
                        select p.expense_version, p.batch_operation_id from expense_payment_events p
                         where p.expense_id = e.id and p.event_type = 'EXPENSE_PAID'
                         order by p.expense_version desc limit 1) active on true
                  left join lateral (
                        select count(*) as count from expense_correction_events c
                         where c.expense_id = e.id and c.from_version >= coalesce(active.expense_version, 0)
                           and c.changed_fields ~ ?) corrections on true
                  left join lateral (
                        select c.actor_user_id, c.corrected_at, c.changed_fields from expense_correction_events c
                         where c.expense_id = e.id and c.from_version >= coalesce(active.expense_version, 0)
                           and c.changed_fields ~ ?
                         order by c.to_version desc limit 1) last_correction on true
                  left join identity_users actor on actor.id = last_correction.actor_user_id
                """ + predicate.where() + " order by " + order + " " + dir + ", lower(e.description) " + dir
                + ", e.id " + dir + " limit ? offset ?", this::payment, parameters.toArray());
        return new PaymentRecordPage(content, total == null ? 0 : total);
    }

    private static final String PAYMENT_FIELDS_PATTERN =
            "(^|,)(" + String.join("|", PaymentRecord.PAYMENT_FIELDS) + ")(,|$)";

    private PaymentRecord payment(ResultSet rs, int row) throws SQLException {
        var purchase = rs.getObject(4, UUID.class);
        var installment = purchase == null ? null : new InstallmentLink(purchase, rs.getInt(5), rs.getInt(6));
        var actor = rs.getObject(21, UUID.class);
        var correction = actor == null ? null : new PaymentRecord.PaymentCorrection(actor, rs.getString(22),
                rs.getTimestamp(23).toInstant(), Arrays.stream(rs.getString(24).split(","))
                        .filter(PaymentRecord.PAYMENT_FIELDS::contains).toList());
        return new PaymentRecord(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), installment,
                rs.getObject(7, LocalDate.class), rs.getBigDecimal(8), rs.getBoolean(9), rs.getBigDecimal(10),
                rs.getObject(11, LocalDate.class), rs.getObject(12, UUID.class), rs.getString(13),
                rs.getObject(14, UUID.class), rs.getString(15), rs.getTimestamp(16).toInstant(), rs.getBoolean(17),
                rs.getString(18), rs.getString(19), rs.getInt(20), correction);
    }
}
