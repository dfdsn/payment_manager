package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.malyah.accountmanager.notifications.domain.WebhookToken;

/**
 * T36: the Evolution API webhook. The Evolution API does not sign its events, so the instance's webhook is
 * configured with a custom header ({@value #TOKEN_HEADER}) carrying a shared secret, checked in constant time before
 * the body is parsed. Only {@code messages.update} events of the configured instance about messages we sent
 * ({@code fromMe}) are applied, in one short transaction; the Evolution statuses are translated to the
 * provider-neutral names ({@code SERVER_ACK} sent, {@code DELIVERY_ACK} delivered, {@code READ}/{@code PLAYED} read,
 * {@code ERROR} failed). Inbound messages, connection events and any other field are ignored and never stored.
 * Nothing here touches expenses or payments (RF-ALT-12). Answers: 404 when the integration is off, 401 for a wrong
 * secret, 413 for an oversized body, 503 when an event may belong to a message whose id is not recorded yet (the
 * Evolution API retries 5xx), 500 when the database fails (also retried; events are deduplicated), 200 otherwise.
 */
final class EvolutionWhatsAppWebhook implements WhatsAppWebhookUseCase {
    static final String TOKEN_HEADER = "X-Webhook-Token";
    static final int MAX_BODY_BYTES = 3 * 1024 * 1024;
    static final int MAX_UPDATES = 1000;
    static final String FAILURE_CODE = "ERROR";
    private static final Map<String, String> STATUSES = Map.of("SERVER_ACK", "sent", "2", "sent",
            "DELIVERY_ACK", "delivered", "3", "delivered", "READ", "read", "4", "read", "PLAYED", "read", "5", "read",
            "ERROR", "failed", "0", "failed");
    private static final Logger LOG = LoggerFactory.getLogger(EvolutionWhatsAppWebhook.class);

    private final EvolutionWhatsAppProperties properties;
    private final WhatsAppDeliveryService service;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    EvolutionWhatsAppWebhook(EvolutionWhatsAppProperties properties, WhatsAppDeliveryService service,
            TransactionTemplate transactions) {
        this.properties = Objects.requireNonNull(properties);
        this.service = Objects.requireNonNull(service);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public Reply receive(byte[] body, String tokenHeader) {
        if (!properties.webhookConfigured()) return new Reply(404, "");
        if (body == null || body.length > MAX_BODY_BYTES) return new Reply(413, "");
        if (!WebhookToken.matches(properties.webhookToken(), tokenHeader)) {
            LOG.warn("whatsapp_webhook_rejected reason=token");
            return new Reply(401, "");
        }
        var updates = statuses(body);
        if (updates.isEmpty()) return new Reply(200, "");
        var now = service.now();
        var outcome = transactions.execute(status -> service.applyStatuses(updates, now));
        LOG.info("whatsapp_webhook applied={} duplicated={} stale={} ignored={} reconciled={} retryLater={}",
                outcome.applied(), outcome.duplicated(), outcome.stale(), outcome.ignored(), outcome.reconciled(),
                outcome.retryLater());
        return outcome.retryLater() ? new Reply(503, "") : new Reply(200, "");
    }

    /** The statuses of our messages on the configured instance; anything unexpected is simply not an update. */
    List<StatusUpdate> statuses(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException invalid) {
            LOG.warn("whatsapp_webhook_ignored reason=invalid_json");
            return List.of();
        }
        var updates = new ArrayList<StatusUpdate>();
        var event = root.path("event").asString("").toLowerCase(Locale.ROOT).replace('_', '.');
        if (!"messages.update".equals(event) || !properties.instance().equals(root.path("instance").asString("")))
            return updates;
        var occurredAt = instant(root.path("date_time"));
        var data = root.path("data");
        var items = data.isArray() ? data : json.createArrayNode().add(data);
        for (var item : items) {
            if (updates.size() >= MAX_UPDATES) break;
            if (!fromMe(item)) continue;
            var id = text(item.path("keyId"), 128);
            if (id == null) id = text(item.path("key").path("id"), 128);
            var status = status(item);
            updates.add(new StatusUpdate(id, status, occurredAt, "failed".equals(status) ? FAILURE_CODE : null,
                    null, false));
        }
        return updates;
    }

    private static boolean fromMe(JsonNode item) {
        var flag = item.has("fromMe") ? item.path("fromMe") : item.path("key").path("fromMe");
        return flag.isBoolean() && flag.booleanValue();
    }

    /** {@code data.status} as text (Evolution v2) or {@code data.update.status} as a number (raw Baileys). */
    private static String status(JsonNode item) {
        var node = item.has("status") ? item.path("status") : item.path("update").path("status");
        var value = node.isString() ? node.stringValue().strip().toUpperCase(Locale.ROOT)
                : node.isIntegralNumber() ? String.valueOf(node.asLong()) : "";
        return STATUSES.get(value);
    }

    private static String text(JsonNode node, int limit) {
        if (!node.isString()) return null;
        var value = node.stringValue().strip();
        return value.isEmpty() || value.length() > limit ? null : value;
    }

    /** {@code date_time} is ISO-8601 with an offset; when it is missing or odd, the service uses its own clock. */
    private static Instant instant(JsonNode node) {
        if (!node.isString()) return null;
        try {
            return OffsetDateTime.parse(node.stringValue().strip()).toInstant();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
