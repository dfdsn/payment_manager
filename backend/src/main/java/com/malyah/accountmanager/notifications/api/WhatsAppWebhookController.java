package com.malyah.accountmanager.notifications.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.WhatsAppWebhookUseCase;

/**
 * H08.4: the Meta webhook, the only path without session and without CSRF (see the security configuration); its
 * authenticity is the verify token (GET) and the body signature (POST). Answers carry no internal detail.
 */
@RestController
@RequestMapping(WhatsAppWebhookController.PATH)
@ConditionalOnProperty(name = "spring.datasource.url")
public class WhatsAppWebhookController {
    public static final String PATH = "/integrations/whatsapp/webhook";
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private final WhatsAppWebhookUseCase useCase;

    WhatsAppWebhookController(WhatsAppWebhookUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        return reply(useCase.verify(mode, token, challenge));
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> receive(@RequestBody(required = false) byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        try {
            return reply(useCase.receive(body, signature));
        } catch (RuntimeException failure) {
            // Meta delivers the event again; statuses already applied are deduplicated.
            LOG.warn("whatsapp_webhook_failed errorCode={}", failure.getClass().getSimpleName());
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body("");
        }
    }

    private static ResponseEntity<String> reply(WhatsAppWebhookUseCase.Reply reply) {
        return ResponseEntity.status(reply.status()).contentType(MediaType.TEXT_PLAIN).body(reply.body());
    }
}
