package com.malyah.accountmanager.notifications.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;
import com.malyah.accountmanager.notifications.application.WhatsAppAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryView;
import com.malyah.accountmanager.notifications.application.WhatsAppTestUnavailableException;
import com.malyah.accountmanager.notifications.application.WhatsAppWebhookUseCase;

/** H08.4 HTTP contract of the tracking, the test and the webhook (security chain: WebhookSecurityHttpTest). */
class WhatsAppHttpTest {
    static final Principal ADMIN = () -> "admin@example.com";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private WhatsAppDeliveryUseCase deliveries;
    private WhatsAppWebhookUseCase webhook;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        deliveries = mock(WhatsAppDeliveryUseCase.class);
        webhook = mock(WhatsAppWebhookUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new WhatsAppDeliveryController(deliveries),
                new WhatsAppWebhookController(webhook))
                .setControllerAdvice(new WhatsAppDeliveryExceptionHandler()).build();
    }

    static WhatsAppDeliveryView view(String state) {
        return new WhatsAppDeliveryView(state, "Aceito pela Meta. A entrega ainda não foi confirmada.", "SUMMARY",
                null, null, "+55 ** *****-4321", 2, Instant.parse("2026-10-05T12:00:10Z"),
                Instant.parse("2026-10-05T12:00:10Z"), Instant.parse("2026-10-05T12:00:11Z"), null, null, null, null,
                List.of(new WhatsAppDeliveryView.Attempt(1, Instant.parse("2026-10-05T12:00:10Z"),
                        Instant.parse("2026-10-05T12:00:11Z"), "ACCEPTED")));
    }

    @Test
    void trackingAndTestReturnTheMaskedView() throws Exception {
        when(deliveries.summaryDelivery("admin@example.com", ID)).thenReturn(view("ACCEPTED"));
        mvc.perform(get("/notifications/reminders/summaries/{id}/whatsapp", ID).principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.recipientMasked").value("+55 ** *****-4321"))
                .andExpect(jsonPath("$.deliveredAt").isEmpty())
                .andExpect(jsonPath("$.attempts[0].outcome").value("ACCEPTED"));
        var key = UUID.randomUUID();
        when(deliveries.sendTest("admin@example.com", key)).thenReturn(view("ACCEPTED"));
        mvc.perform(post("/notifications/settings/whatsapp/test-message").header("Idempotency-Key", key)
                .principal(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"));
    }

    @Test
    void errorsHaveStableCodes() throws Exception {
        var guestOnly = UUID.randomUUID();
        when(deliveries.summaryDelivery("admin@example.com", guestOnly))
                .thenThrow(new WhatsAppAdministratorRequiredException());
        mvc.perform(get("/notifications/reminders/summaries/{id}/whatsapp", guestOnly).principal(ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_ADMINISTRATOR_REQUIRED"));
        when(deliveries.summaryDelivery("admin@example.com", ID)).thenThrow(new ReminderSummaryNotFoundException());
        mvc.perform(get("/notifications/reminders/summaries/{id}/whatsapp", ID).principal(ADMIN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REMINDER_SUMMARY_NOT_FOUND"));
        mvc.perform(get("/notifications/reminders/summaries/nao-e-id/whatsapp").principal(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WHATSAPP_REQUEST_INVALID"));
        mvc.perform(post("/notifications/settings/whatsapp/test-message").principal(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WHATSAPP_REQUEST_INVALID"));
        var key = UUID.randomUUID();
        when(deliveries.sendTest("admin@example.com", key)).thenThrow(
                new WhatsAppTestUnavailableException("WHATSAPP_TEST_TOO_SOON", "Aguarde um minuto entre dois testes."));
        mvc.perform(post("/notifications/settings/whatsapp/test-message").header("Idempotency-Key", key)
                .principal(ADMIN)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WHATSAPP_TEST_TOO_SOON"));
        var other = UUID.randomUUID();
        when(deliveries.sendTest("admin@example.com", other)).thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(post("/notifications/settings/whatsapp/test-message").header("Idempotency-Key", other)
                .principal(ADMIN)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void webhookPassesTheRawBodyAndTheSignatureAndReturnsPlainText() throws Exception {
        when(webhook.verify("subscribe", "v", "123")).thenReturn(new WhatsAppWebhookUseCase.Reply(200, "123"));
        mvc.perform(get("/integrations/whatsapp/webhook").param("hub.mode", "subscribe")
                .param("hub.verify_token", "v").param("hub.challenge", "123"))
                .andExpect(status().isOk()).andExpect(content().string("123"))
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
        var body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        when(webhook.receive(body, "sha256=ab")).thenReturn(new WhatsAppWebhookUseCase.Reply(401, ""));
        mvc.perform(post("/integrations/whatsapp/webhook").content(body).contentType("application/json")
                .header("X-Hub-Signature-256", "sha256=ab")).andExpect(status().isUnauthorized());
        when(webhook.receive(any(), eq("sha256=cd"))).thenThrow(new IllegalStateException("database down"));
        mvc.perform(post("/integrations/whatsapp/webhook").content(body).contentType("application/json")
                .header("X-Hub-Signature-256", "sha256=cd")).andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
        verifyNoInteractions(deliveries);
    }
}
