package com.malyah.accountmanager.expenses.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;

/**
 * H03.4 predicate over {@code expense_entries e}, always bound to one space. The list, its count and the E06 totals
 * use this single implementation so that a filter can never select one population for the page and another for
 * the totals.
 */
final class ExpenseSelectionPredicate {
    private final String where;
    private final List<Object> parameters;

    private ExpenseSelectionPredicate(String where, List<Object> parameters) {
        this.where = where;
        this.parameters = List.copyOf(parameters);
    }

    static ExpenseSelectionPredicate of(UUID spaceId, ExpenseSelection selection) {
        var where = new StringBuilder(" where e.space_id = ?");
        var parameters = new ArrayList<Object>();
        parameters.add(spaceId);
        if (selection.search() != null && !selection.search().isBlank()) {
            where.append(" and lower(e.description) like lower(?) escape '!'");
            parameters.add("%" + selection.search().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        var dateColumn = selection.dateBasis() == ExpenseDateBasis.PAYMENT_DATE ? "e.payment_date" : "e.reference_date";
        if (selection.dateFrom() != null) {
            where.append(" and ").append(dateColumn).append(" >= ?");
            parameters.add(selection.dateFrom());
        }
        if (selection.dateTo() != null) {
            where.append(" and ").append(dateColumn).append(" <= ?");
            parameters.add(selection.dateTo());
        }
        if (selection.withoutCategory()) where.append(" and e.category_id is null");
        else if (selection.categoryId() != null) {
            where.append(" and e.category_id = ?");
            parameters.add(selection.categoryId());
        }
        if (selection.withoutResponsible()) where.append(" and e.responsible_user_id is null");
        else if (selection.responsibleUserId() != null) {
            where.append(" and e.responsible_user_id = ?");
            parameters.add(selection.responsibleUserId());
        }
        if (selection.payerUserId() != null) {
            where.append(" and e.paid_by_user_id = ?");
            parameters.add(selection.payerUserId());
        }
        switch (selection.status()) {
            case ACTIVE -> where.append(" and e.status <> 'CANCELLED'");
            case PENDING -> where.append(" and e.status = 'PENDING'");
            case OVERDUE -> {
                where.append(" and e.status = 'PENDING' and e.due_date < ?");
                parameters.add(selection.today());
            }
            case PAID -> where.append(" and e.status = 'PAID'");
            case CANCELLED -> where.append(" and e.status = 'CANCELLED'");
            case ALL -> { }
        }
        return new ExpenseSelectionPredicate(where.toString(), parameters);
    }

    String where() {
        return where;
    }

    List<Object> parameters() {
        return parameters;
    }

    /** The list order (H03.4), total and stable: the chosen key, then creation instant and id. */
    static String orderBy(com.malyah.accountmanager.expenses.application.ExpenseSort sort,
            com.malyah.accountmanager.expenses.application.SortDirection direction) {
        var key = switch (sort) {
            case REFERENCE_DATE -> "e.reference_date";
            case AMOUNT -> "e.charge_amount";
            case DESCRIPTION -> "lower(e.description)";
        };
        var way = direction.name();
        return " order by " + key + " " + way + ", e.created_at " + way + ", e.id " + way;
    }
}
