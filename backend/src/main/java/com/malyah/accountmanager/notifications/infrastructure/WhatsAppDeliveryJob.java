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
 * attempts become uncertain first; nothing is ever resent. Logs carry ids, states and codes only.
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
        var result = sender.send(ready.message());
        var recorded = transactions.execute(status -> service.record(ready, result, service.now()));
        LOG.info("whatsapp_delivery summaryId={} deliveryId={} status={} errorCode={}", summaryId,
                ready.deliveryId(), recorded, result.errorCode());
        return String.valueOf(recorded);
    }
}
