package com.malyah.accountmanager.notifications.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryView;

/** Reads in one read-only REPEATABLE READ transaction: expenses, forecasts and settings from one moment. */
final class TransactionalReminderSummaryUseCase implements ReminderSummaryUseCase {
    private final ReminderSummaryUseCase delegate;
    private final TransactionTemplate reads;

    TransactionalReminderSummaryUseCase(ReminderSummaryUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.reads = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ReminderSummaryView.Preview preview(String actorEmail, String date, String slot) {
        return reads.execute(status -> delegate.preview(actorEmail, date, slot));
    }

    @Override
    public ReminderSummaryView summary(String actorEmail, UUID summaryId) {
        return reads.execute(status -> delegate.summary(actorEmail, summaryId));
    }
}
