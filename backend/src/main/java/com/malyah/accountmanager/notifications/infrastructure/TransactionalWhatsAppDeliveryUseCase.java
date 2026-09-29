package com.malyah.accountmanager.notifications.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryView;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;

/** Reads in one read-only transaction; the test claims and records in short transactions around the call. */
final class TransactionalWhatsAppDeliveryUseCase implements WhatsAppDeliveryUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionalWhatsAppDeliveryUseCase.class);
    private final WhatsAppDeliveryService service;
    private final WhatsAppSender sender;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    TransactionalWhatsAppDeliveryUseCase(WhatsAppDeliveryService service, WhatsAppSender sender,
            TransactionTemplate transactions) {
        this.service = Objects.requireNonNull(service);
        this.sender = Objects.requireNonNull(sender);
        var manager = Objects.requireNonNull(transactions.getTransactionManager());
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writes = new TransactionTemplate(manager);
    }

    @Override
    public WhatsAppDeliveryView summaryDelivery(String actorEmail, UUID summaryId) {
        return reads.execute(status -> service.summaryDelivery(actorEmail, summaryId));
    }

    @Override
    public WhatsAppDeliveryView sendTest(String actorEmail, UUID idempotencyKey) {
        var preparation = writes.execute(status -> service.prepareTest(actorEmail, idempotencyKey, service.now()));
        if (preparation instanceof WhatsAppDeliveryService.ExistingTest existing) return existing.view();
        var ready = (WhatsAppDeliveryService.Ready) Objects.requireNonNull(preparation);
        var result = sender.send(ready.message());
        var recorded = writes.execute(status -> service.record(ready, result, service.now()));
        LOG.info("whatsapp_test deliveryId={} status={} errorCode={}", ready.deliveryId(), recorded,
                result.errorCode());
        return reads.execute(status -> service.testView(ready.spaceId(), idempotencyKey));
    }
}
