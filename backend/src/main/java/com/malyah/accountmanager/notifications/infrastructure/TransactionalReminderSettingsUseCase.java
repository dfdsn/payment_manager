package com.malyah.accountmanager.notifications.infrastructure;

import java.util.Objects;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSettingsView;

/**
 * Each change (settings, consent, audit event and idempotency record) is one READ COMMITTED transaction: all or
 * nothing. Reads use one read-only REPEATABLE READ transaction, so the settings and the consent come from one moment.
 */
final class TransactionalReminderSettingsUseCase implements ReminderSettingsUseCase {
    private final ReminderSettingsUseCase delegate;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    TransactionalReminderSettingsUseCase(ReminderSettingsUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        var manager = Objects.requireNonNull(transactions.getTransactionManager());
        this.writes = new TransactionTemplate(manager);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ReminderSettingsView view(String actorEmail) {
        return reads.execute(status -> delegate.view(actorEmail));
    }

    @Override
    public ReminderSettingsView.EventList events(String actorEmail) {
        return reads.execute(status -> delegate.events(actorEmail));
    }

    @Override
    public ReminderSettingsView changeSchedule(String actorEmail, ReminderSettingsCommand command) {
        return writes.execute(status -> delegate.changeSchedule(actorEmail, command));
    }

    @Override
    public ReminderSettingsView changeRecipient(String actorEmail, ReminderSettingsCommand command) {
        return writes.execute(status -> delegate.changeRecipient(actorEmail, command));
    }

    @Override
    public ReminderSettingsView grantConsent(String actorEmail, ReminderSettingsCommand command) {
        return writes.execute(status -> delegate.grantConsent(actorEmail, command));
    }

    @Override
    public ReminderSettingsView revokeConsent(String actorEmail, ReminderSettingsCommand command) {
        return writes.execute(status -> delegate.revokeConsent(actorEmail, command));
    }

    @Override
    public ReminderSettingsView changeChannel(String actorEmail, ReminderSettingsCommand command) {
        return writes.execute(status -> delegate.changeChannel(actorEmail, command));
    }
}
