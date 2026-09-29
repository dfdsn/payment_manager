package com.malyah.accountmanager.notifications.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryView;

/**
 * H08.4, administrator only: the WhatsApp tracking of a summary and the test message to the consented number. The
 * space comes from the session; the number is masked and no provider id or text is returned.
 */
@RestController
@ConditionalOnProperty(name = "spring.datasource.url")
class WhatsAppDeliveryController {
    private final WhatsAppDeliveryUseCase useCase;

    WhatsAppDeliveryController(WhatsAppDeliveryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/notifications/reminders/summaries/{id}/whatsapp")
    WhatsAppDeliveryView summary(Principal principal, @PathVariable UUID id) {
        return useCase.summaryDelivery(principal.getName(), id);
    }

    @PostMapping("/notifications/settings/whatsapp/test-message")
    WhatsAppDeliveryView test(Principal principal, @RequestHeader("Idempotency-Key") UUID key) {
        return useCase.sendTest(principal.getName(), key);
    }
}
