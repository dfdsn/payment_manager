package com.malyah.accountmanager.notifications.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.MemberNotificationView;

/** One transaction per operation; the list, its total and the unread count are read in one read-only transaction. */
final class TransactionalMemberNotificationUseCase implements MemberNotificationUseCase {
    private final MemberNotificationUseCase delegate;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    TransactionalMemberNotificationUseCase(MemberNotificationUseCase delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        var manager = Objects.requireNonNull(transactions.getTransactionManager());
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        this.writes = new TransactionTemplate(manager);
    }

    @Override
    public MemberNotificationView.Page list(String actorEmail, String view, Integer page, Integer size) {
        return reads.execute(status -> delegate.list(actorEmail, view, page, size));
    }

    @Override
    public MemberNotificationView.UnreadCount unreadCount(String actorEmail) {
        return reads.execute(status -> delegate.unreadCount(actorEmail));
    }

    @Override
    public MemberNotificationView read(String actorEmail, UUID notificationId) {
        return writes.execute(status -> delegate.read(actorEmail, notificationId));
    }

    @Override
    public MemberNotificationView dismiss(String actorEmail, UUID notificationId) {
        return writes.execute(status -> delegate.dismiss(actorEmail, notificationId));
    }
}
