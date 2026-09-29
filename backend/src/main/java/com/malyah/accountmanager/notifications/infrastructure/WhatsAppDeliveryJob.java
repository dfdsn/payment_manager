package com.malyah.accountmanager.notifications.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.Ready;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;

/**
 * H08.4 polling job. For each planned summary: a short transaction revalidates and claims the delivery, the
 * provider is called outside any transaction, and another short transaction records the result. Interrupted
 * attempts become uncertain first and are never resent. H08.5: then each delivery waiting for a retry is
 * revalidated; it is closed when it is no longer valid or its window ended (also after a restart), and attempted
 * again, as the next attempt of the same delivery, only when its instant came. Logs carry ids, states and codes.
 */
public final class WhatsAppDeliveryJob {
    static final int BATCH = 20;
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppDeliveryJob.class);
    private final WhatsAppDeliveryService service;
    private final WhatsAppSender sender;
    private final TransactionTemplate transactions;

    WhatsAppDeliveryJob(WhatsAppDeliveryService service, WhatsAppSender sender, TransactionTemplate transactions) {
        this.service = Objects.requireNonNull(service);
        this.sender = Objects.requireNonNull(sender);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Scheduled(fixedDelayString = "${app.jobs.whatsapp.fixed-delay-ms:30000}",
            initialDelayString = "${app.jobs.whatsapp.initial-delay-ms:45000}")
    public void poll() {
        var expired = Objects.requireNonNull(transactions.execute(status -> service.expireStale(service.now())));
        if (expired > 0) LOG.warn("whatsapp_attempts_uncertain count={}", expired);
        for (var summaryId : Objects.requireNonNull(transactions.execute(status -> service.pending(BATCH)))) {
            try {
                deliver(summaryId);
            } catch (RuntimeException error) {
                LOG.warn("whatsapp_delivery_failed summaryId={} errorCode={}", summaryId,
                        error.getClass().getSimpleName());
            }
        }
        for (var deliveryId : Objects.requireNonNull(transactions.execute(status -> service.retrying(BATCH)))) {
            try {
                retry(deliveryId);
            } catch (RuntimeException error) {
                LOG.warn("whatsapp_retry_failed deliveryId={} errorCode={}", deliveryId,
                        error.getClass().getSimpleName());
            }
        }
    }

    /** H08.5: one waiting delivery: revalidate, then close it, leave it waiting or attempt it again. */
    String retry(UUID deliveryId) {
        WhatsAppDeliveryService.Preparation preparation;
        try {
            preparation = transactions.execute(status -> service.prepareRetry(deliveryId, service.now()));
        } catch (ConcurrencyFailureException | DataIntegrityViolationException lostRace) {
            return "ALREADY_CLAIMED";
        }
        if (preparation instanceof WhatsAppDeliveryService.Skipped skipped) {
            LOG.info("whatsapp_retry_closed deliveryId={} reason={}", deliveryId, skipped.reason());
            return "CLOSED";
        }
        if (preparation instanceof WhatsAppDeliveryService.NotDue) return "WAITING";
        if (!(preparation instanceof Ready ready)) return "ALREADY_CLAIMED";
        return call(ready);
    }

    /** One summary: claim, call, record. Returns the recorded state name, or what stopped it. */
    String deliver(UUID summaryId) {
        WhatsAppDeliveryService.Preparation preparation;
        try {
            preparation = transactions.execute(status -> service.prepare(summaryId, service.now()));
        } catch (ConcurrencyFailureException | DataIntegrityViolationException lostRace) {
            return "ALREADY_CLAIMED";
        }
        if (preparation instanceof WhatsAppDeliveryService.Skipped skipped) {
            LOG.info("whatsapp_delivery summaryId={} status=SKIPPED reason={}", summaryId, skipped.reason());
            return "SKIPPED";
        }
        if (!(preparation instanceof Ready ready)) return "ALREADY_CLAIMED";
        return call(ready);
    }

    /**
     * The call happens after the claim was committed and the result is recorded in another transaction. If the
     * process stops in between, the attempt stays {@code ATTEMPTING} and later becomes uncertain: it is never
     * attempted again, because the provider may have accepted it.
     */
    private String call(Ready ready) {
        var result = sender.send(ready.message());
        var recorded = transactions.execute(status -> service.record(ready, result, service.now()));
        LOG.info("whatsapp_delivery summaryId={} deliveryId={} attempt={} status={} errorCode={}", ready.summaryId(),
                ready.deliveryId(), ready.attemptNumber(), recorded, result.errorCode());
        return String.valueOf(recorded);
    }
}
