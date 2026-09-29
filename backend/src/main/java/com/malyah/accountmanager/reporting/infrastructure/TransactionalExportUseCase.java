package com.malyah.accountmanager.reporting.infrastructure;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.reporting.application.CsvFile;
import com.malyah.accountmanager.reporting.application.ExpenseExportQuery;
import com.malyah.accountmanager.reporting.application.ExportUseCase;
import com.malyah.accountmanager.reporting.application.ForecastExportQuery;

/**
 * Runs each export in one read-only REPEATABLE READ transaction: the count checked against the limit and every row
 * written come from the same snapshot, so a concurrent change can neither cut the file nor push it past the limit.
 */
final class TransactionalExportUseCase implements ExportUseCase {
    private final ExportUseCase delegate;
    private final TransactionTemplate transactions;

    TransactionalExportUseCase(ExportUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions = transactions;
    }

    @Override
    public CsvFile expenses(String actorEmail, ExpenseExportQuery query) {
        return transactions.execute(status -> delegate.expenses(actorEmail, query));
    }

    @Override
    public CsvFile forecasts(String actorEmail, ForecastExportQuery query) {
        return transactions.execute(status -> delegate.forecasts(actorEmail, query));
    }
}
