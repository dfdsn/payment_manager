package com.malyah.accountmanager.reporting.infrastructure;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

/**
 * Runs each report in one read-only REPEATABLE READ transaction, so the month totals and the previous pending
 * entries come from the same snapshot even while members settle or correct expenses.
 */
final class TransactionalReportingUseCase implements ReportingUseCase {
    private final ReportingUseCase delegate;
    private final TransactionTemplate transactions;

    TransactionalReportingUseCase(ReportingUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions = transactions;
    }

    @Override
    public DueDashboardView dueDashboard(String actorEmail, ReportFilters filters) {
        return transactions.execute(status -> delegate.dueDashboard(actorEmail, filters));
    }
}
