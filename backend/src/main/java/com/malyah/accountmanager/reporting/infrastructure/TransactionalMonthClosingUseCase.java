package com.malyah.accountmanager.reporting.infrastructure;

import java.util.Objects;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.CloseMonthResult;
import com.malyah.accountmanager.reporting.application.ClosingVersionListView;
import com.malyah.accountmanager.reporting.application.ClosingVersionView;
import com.malyah.accountmanager.reporting.application.GenerateVersionCommand;
import com.malyah.accountmanager.reporting.application.MonthClosingListView;
import com.malyah.accountmanager.reporting.application.MonthClosingUseCase;
import com.malyah.accountmanager.reporting.application.MonthClosingView;

/**
 * A closing or a new version writes header, version, categories, lines, audit event and idempotency record in one READ COMMITTED
 * transaction: all or nothing. The entries of the month are read by one statement, which is a consistent snapshot on
 * its own; the header insert waits for a concurrent closing of the same month and then sees it. Reading a closing
 * runs in one read-only REPEATABLE READ transaction, so the saved and the current data come from the same moment.
 */
final class TransactionalMonthClosingUseCase implements MonthClosingUseCase {
    private final MonthClosingUseCase delegate;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    TransactionalMonthClosingUseCase(MonthClosingUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        var manager = Objects.requireNonNull(transactions.getTransactionManager());
        this.writes = new TransactionTemplate(manager);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public MonthClosingView view(String actorEmail, String month) {
        return reads.execute(status -> delegate.view(actorEmail, month));
    }

    @Override
    public MonthClosingListView list(String actorEmail, String year) {
        return reads.execute(status -> delegate.list(actorEmail, year));
    }

    @Override
    public CloseMonthResult close(String actorEmail, CloseMonthCommand command) {
        return writes.execute(status -> delegate.close(actorEmail, command));
    }

    @Override
    public CloseMonthResult generateVersion(String actorEmail, GenerateVersionCommand command) {
        return writes.execute(status -> delegate.generateVersion(actorEmail, command));
    }

    @Override
    public ClosingVersionListView versions(String actorEmail, String month) {
        return reads.execute(status -> delegate.versions(actorEmail, month));
    }

    @Override
    public ClosingVersionView version(String actorEmail, String month, String number) {
        return reads.execute(status -> delegate.version(actorEmail, month, number));
    }
}
