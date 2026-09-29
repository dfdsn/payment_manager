package com.malyah.accountmanager.expenses.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket;
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
}
