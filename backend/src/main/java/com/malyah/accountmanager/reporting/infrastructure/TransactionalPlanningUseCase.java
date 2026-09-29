package com.malyah.accountmanager.reporting.infrastructure;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.reporting.application.PlanningQuery;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.PlanningView;

/**
 * Runs the planning in one read-only REPEATABLE READ transaction: the materialized totals, the forecasts (which
 * depend on which occurrences are materialized) and the page all come from the same snapshot, so a generation or
 * anticipation that commits meanwhile cannot make a value appear twice or disappear.
 */
final class TransactionalPlanningUseCase implements PlanningUseCase {
    private final PlanningUseCase delegate;
    private final TransactionTemplate transactions;

    TransactionalPlanningUseCase(PlanningUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions = transactions;
    }

    @Override
    public PlanningView planning(String actorEmail, PlanningQuery query) {
        return transactions.execute(status -> delegate.planning(actorEmail, query));
    }
}
