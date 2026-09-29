package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.StatusUpdate;
import com.malyah.accountmanager.notifications.application.WhatsAppWebhookUseCase;
import com.malyah.accountmanager.notifications.domain.WebhookSignature;

/**
 * H08.4: the Meta webhook. The signature over the raw body is checked before the body is parsed; only then are the
 * {@code statuses} of the configured phone number applied, in one short transaction. Inbound user messages and any
 * other field are ignored and never stored. Nothing here touches expenses or payments (RF-ALT-12).
 * Answers: 404 when the integration is off, 403 for a wrong verification, 401 for a bad signature, 413 for an
 * oversized body, 503 when an event may belong to a message whose id is not recorded yet (Meta retries), 500 when
 * the database fails (Meta retries; events are deduplicated), 200 otherwise.
 */
final class MetaWhatsAppWebhook implements WhatsAppWebhookUseCase {
    static final int MAX_BODY_BYTES = 3 * 1024 * 1024;
    static final int MAX_UPDATES = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(MetaWhatsAppWebhook.class);

    private final MetaWhatsAppProperties properties;
    private final WhatsAppDeliveryService service;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    MetaWhatsAppWebhook(MetaWhatsAppProperties properties, WhatsAppDeliveryService service,
            TransactionTemplate transactions) {
        this.properties = Objects.requireNonNull(properties);
        this.service = Objects.requireNonNull(service);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public Reply verify(String mode, String verifyToken, String challenge) {
        if (!properties.webhookConfigured()) return new Reply(404, "");
        if (!"subscribe".equals(mode) || challenge == null || challenge.isEmpty() || challenge.length() > 256
                || !WebhookSignature.sameSecret(properties.verifyToken(), verifyToken))
            return new Reply(403, "");
        return new Reply(200, challenge);
    }

    @Override
    public Reply receive(byte[] body, String signatureHeader) {
        if (!properties.webhookConfigured()) return new Reply(404, "");
        if (body == null || body.length > MAX_BODY_BYTES) return new Reply(413, "");
        if (!WebhookSignature.matches(properties.appSecret(), body, signatureHeader)) {
            LOG.warn("whatsapp_webhook_rejected reason=signature");
            return new Reply(401, "");
        }
        var updates = statuses(body);
        if (updates.isEmpty()) return new Reply(200, "");
        var now = service.now();
        var outcome = transactions.execute(status -> service.applyStatuses(updates, now));
        LOG.info("whatsapp_webhook applied={} duplicated={} stale={} ignored={} retryLater={}", outcome.applied(),
                outcome.duplicated(), outcome.stale(), outcome.ignored(), outcome.retryLater());
        return outcome.retryLater() ? new Reply(503, "") : new Reply(200, "");
    }

    /** The {@code statuses} of the configured number; anything unexpected is simply not an update. */
    List<StatusUpdate> statuses(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException invalid) {
            LOG.warn("whatsapp_webhook_ignored reason=invalid_json");
            return List.of();
        }
        var updates = new ArrayList<StatusUpdate>();
        if (!"whatsapp_business_account".equals(root.path("object").asString(""))) return updates;
        for (var entry : root.path("entry"))
            for (var change : entry.path("changes")) {
                if (!"messages".equals(change.path("field").asString(""))) continue;
                var value = change.path("value");
                if (!properties.phoneNumberId().equals(value.path("metadata").path("phone_number_id").asString("")))
                    continue;
                for (var status : value.path("statuses")) {
                    if (updates.size() >= MAX_UPDATES) return updates;
                    updates.add(new StatusUpdate(text(status.path("id"), 128), text(status.path("status"), 16),
                            instant(status.path("timestamp")), errorCode(status.path("errors").path(0).path("code"))));
                }
            }
        return updates;
    }

    private static String text(JsonNode node, int limit) {
        if (!node.isString()) return null;
        var value = node.stringValue().strip();
        return value.isEmpty() || value.length() > limit ? null : value;
    }

    private static Instant instant(JsonNode node) {
        var text = node.isString() ? node.stringValue() : node.isNumber() ? node.asString() : "";
        if (!text.matches("[0-9]{1,12}")) return null;
        return Instant.ofEpochSecond(Long.parseLong(text));
    }

    private static String errorCode(JsonNode node) {
        if (!node.isNumber() || !node.canConvertToLong()) return null;
        var value = String.valueOf(node.asLong());
        return value.length() > 16 ? null : value;
    }
}
