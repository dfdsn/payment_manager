package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseDayCount;
import com.malyah.accountmanager.expenses.application.ExpensePlanningQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PlanningExpense;
import com.malyah.accountmanager.expenses.application.PlanningExpenseBucket;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * H06.3 reads of the materialized part of the planning, with the shared {@link ExpenseSelectionPredicate}. Sums
 * are done by PostgreSQL over the whole selection; the page order (reference date, id) is total and stable.
 */
public final class JdbcExpensePlanningQueries implements ExpensePlanningQueries {
    private final JdbcTemplate jdbc;

    public JdbcExpensePlanningQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PlanningExpenseBucket> planningTotals(UUID spaceId, ExpenseSelection selection) {
        var predicate = predicate(spaceId, selection);
        return jdbc.query("""
                select date_trunc('month', e.reference_date)::date, e.origin, e.status, e.charge_confirmed, count(*),
                       coalesce(sum(e.charge_amount), 0), coalesce(sum(e.paid_amount), 0)
                  from expense_entries e
                """ + predicate.where() + " group by 1, 2, 3, 4 order by 1, 2, 3, 4",
                (rs, row) -> new PlanningExpenseBucket(YearMonth.from(rs.getObject(1, LocalDate.class)),
                        rs.getString(2), ExpenseStatus.valueOf(rs.getString(3)), rs.getBoolean(4), rs.getLong(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)),
                predicate.parameters().toArray());
    }

    @Override
    public List<ExpenseDayCount> dailyCounts(UUID spaceId, ExpenseSelection selection) {
        var predicate = predicate(spaceId, selection);
        return jdbc.query("select e.reference_date, count(*) from expense_entries e" + predicate.where()
                + " group by 1 order by 1", (rs, row) -> new ExpenseDayCount(rs.getObject(1, LocalDate.class),
                        rs.getLong(2)), predicate.parameters().toArray());
    }

    @Override
    public List<PlanningExpense> planningEntries(UUID spaceId, ExpenseSelection selection, long offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid window.");
        var predicate = predicate(spaceId, selection);
        var parameters = new ArrayList<Object>(predicate.parameters());
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query("""
                select e.id, e.origin, e.installment_purchase_id, e.installment_number, e.installment_count,
                       e.description, e.reference_date, e.due_date, e.charge_amount, e.charge_confirmed, e.status,
                       e.paid_amount, e.payment_date, category.name, responsible.display_name
                  from expense_entries e
                  left join expense_categories category on category.id = e.category_id and category.space_id = e.space_id
                  left join identity_users responsible on responsible.id = e.responsible_user_id
                """ + predicate.where() + " order by e.reference_date, e.id limit ? offset ?", this::entry,
                parameters.toArray());
    }

    private static ExpenseSelectionPredicate predicate(UUID spaceId, ExpenseSelection selection) {
        if (selection.dateBasis() != ExpenseDateBasis.DUE_DATE || selection.status() != ExpenseStatusFilter.ACTIVE
                || selection.dateFrom() == null || selection.dateTo() == null)
            throw new IllegalArgumentException("The planning selects active expenses of a closed period by due date.");
        return ExpenseSelectionPredicate.of(spaceId, selection);
    }

    private PlanningExpense entry(ResultSet rs, int row) throws SQLException {
        var purchase = rs.getObject(3, UUID.class);
        var installment = purchase == null ? null : new InstallmentLink(purchase, rs.getInt(4), rs.getInt(5));
        return new PlanningExpense(rs.getObject(1, UUID.class), rs.getString(2), installment, rs.getString(6),
                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getBigDecimal(9),
                rs.getBoolean(10), ExpenseStatus.valueOf(rs.getString(11)), rs.getBigDecimal(12),
                rs.getObject(13, LocalDate.class), rs.getString(14), rs.getString(15));
    }
}
