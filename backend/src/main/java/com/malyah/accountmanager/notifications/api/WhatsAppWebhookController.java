package com.malyah.accountmanager.notifications.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.WhatsAppWebhookUseCase;

/**
 * T36: the Evolution API webhook, the only path without session and without CSRF (see the security configuration);
 * its authenticity is the shared secret in the {@code X-Webhook-Token} header, configured on the instance's webhook.
 * Answers carry no internal detail.
 */
@RestController
@RequestMapping(WhatsAppWebhookController.PATH)
@ConditionalOnProperty(name = "spring.datasource.url")
public class WhatsAppWebhookController {
    public static final String PATH = "/integrations/whatsapp/webhook";
    public static final String TOKEN_HEADER = "X-Webhook-Token";
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private final WhatsAppWebhookUseCase useCase;

    WhatsAppWebhookController(WhatsAppWebhookUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> receive(@RequestBody(required = false) byte[] body,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        try {
            return reply(useCase.receive(body, token));
        } catch (RuntimeException failure) {
            // The Evolution API delivers the event again; statuses already applied are deduplicated.
            LOG.warn("whatsapp_webhook_failed errorCode={}", failure.getClass().getSimpleName());
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body("");
        }
    }

    private static ResponseEntity<String> reply(WhatsAppWebhookUseCase.Reply reply) {
        return ResponseEntity.status(reply.status()).contentType(MediaType.TEXT_PLAIN).body(reply.body());
    }
}
