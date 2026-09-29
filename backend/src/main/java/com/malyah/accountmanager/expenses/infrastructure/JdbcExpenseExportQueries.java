package com.malyah.accountmanager.expenses.infrastructure;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseExportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExportedExpense;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/** H06.4 reads with the list predicate and order; rows are handed over one by one with a bounded fetch size. */
public final class JdbcExpenseExportQueries implements ExpenseExportQueries {
    private static final int FETCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate streaming;

    /** The streaming template shares the data source, so it joins the caller's transaction and snapshot. */
    public JdbcExpenseExportQueries(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.streaming = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.streaming.setFetchSize(FETCH_SIZE);
    }

    @Override
    public long count(UUID spaceId, ExpenseSelection selection) {
        var predicate = ExpenseSelectionPredicate.of(spaceId, selection);
        var total = jdbc.queryForObject("select count(*) from expense_entries e" + predicate.where(), Long.class,
                predicate.parameters().toArray());
        return total == null ? 0 : total;
    }

    @Override
    public void export(UUID spaceId, ExpenseSelection selection, ExpenseSort sort, SortDirection direction, int limit,
            Consumer<ExportedExpense> sink) {
        if (limit < 1) throw new IllegalArgumentException("The limit must be positive.");
        var predicate = ExpenseSelectionPredicate.of(spaceId, selection);
        var parameters = new ArrayList<Object>(predicate.parameters());
        parameters.add(limit);
        streaming.query("""
                select e.id, e.origin, e.installment_purchase_id, e.installment_number, e.installment_count,
                       e.description, category.name, e.due_date, e.charge_amount, e.charge_confirmed, e.status,
                       e.paid_amount, e.payment_date, responsible.display_name, payer.display_name
                  from expense_entries e
                  left join expense_categories category on category.id = e.category_id and category.space_id = e.space_id
                  left join identity_users responsible on responsible.id = e.responsible_user_id
                  left join identity_users payer on payer.id = e.paid_by_user_id
                """ + predicate.where() + ExpenseSelectionPredicate.orderBy(sort, direction) + " limit ?", rs -> {
                    var purchase = rs.getObject(3, UUID.class);
                    sink.accept(new ExportedExpense(rs.getObject(1, UUID.class), rs.getString(2),
                            purchase == null ? null : new InstallmentLink(purchase, rs.getInt(4), rs.getInt(5)),
                            rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getBigDecimal(9),
                            rs.getBoolean(10), ExpenseStatus.valueOf(rs.getString(11)), rs.getBigDecimal(12),
                            rs.getObject(13, LocalDate.class), rs.getString(14), rs.getString(15)));
                }, parameters.toArray());
    }
}
