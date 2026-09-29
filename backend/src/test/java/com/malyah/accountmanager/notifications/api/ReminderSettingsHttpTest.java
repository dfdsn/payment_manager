package com.malyah.accountmanager.notifications.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.NotificationAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsIdempotencyConflictException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSettingsVersionConflictException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsView;
import com.malyah.accountmanager.notifications.application.WhatsAppConsentNotActiveException;
import com.malyah.accountmanager.notifications.application.WhatsAppRecipientMismatchException;
import com.malyah.accountmanager.notifications.domain.NotificationValidationException;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;

/** H08.1 HTTP contract: routes, request bodies, idempotency header and error codes. */
class ReminderSettingsHttpTest {
    static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final Principal ADMIN = () -> "admin@example.com";
    private ReminderSettingsUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ReminderSettingsUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReminderSettingsController(useCase))
                .setControllerAdvice(new NotificationsApiExceptionHandler()).build();
    }

    static ReminderSettingsView view() {
        return new ReminderSettingsView(true, "America/Sao_Paulo", 3, Instant.parse("2026-09-29T12:00:00Z"),
                new ReminderSettingsView.Schedule("08:30", "20:00", "09:00", "18:00"),
                new ReminderSettingsView.WhatsApp(true, "+5511987654321", "+55 11 98765-4321", "4321", true,
                        new ReminderSettingsView.Consent(true, Instant.parse("2026-09-29T12:00:00Z"), "Admin", "4321"),
                        new ReminderSettingsView.Provider(false, "PROVIDER_DISABLED", "indisponível"),
                        "PROVIDER_UNAVAILABLE", "WHATSAPP-RESUMOS-V1", "Autorizo...", null));
    }

    @Test
    void readsTheSettingsWithoutAnyCredentialField() throws Exception {
        when(useCase.view("admin@example.com")).thenReturn(view());
        mvc.perform(get("/notifications/settings").principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.schedule.firstTime").value("08:30"))
                .andExpect(jsonPath("$.schedule.defaultSecondTime").value("18:00"))
                .andExpect(jsonPath("$.whatsapp.recipient").value("+5511987654321"))
                .andExpect(jsonPath("$.whatsapp.state").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.whatsapp.consent.grantedAt").exists())
                .andExpect(jsonPath("$.whatsapp.provider.code").value("PROVIDER_DISABLED"))
                .andExpect(jsonPath("$.whatsapp.token").doesNotExist())
                .andExpect(jsonPath("$.whatsapp.provider.accessToken").doesNotExist());
    }

    @Test
    void routesEachChangeWithItsBodyAndKey() throws Exception {
        when(useCase.changeSchedule(any(), any())).thenReturn(view());
        when(useCase.changeRecipient(any(), any())).thenReturn(view());
        when(useCase.grantConsent(any(), any())).thenReturn(view());
        when(useCase.revokeConsent(any(), any())).thenReturn(view());
        when(useCase.changeChannel(any(), any())).thenReturn(view());
        when(useCase.events(any())).thenReturn(new ReminderSettingsView.EventList(List.of(
                new ReminderSettingsView.EventItem("RECIPIENT_CHANGED", "Admin", Instant.parse("2026-09-29T12:00:00Z"),
                        0, 1, "nenhum -> +55 ** *****-4321"))));

        send(put("/notifications/settings/schedule"), "{\"expectedVersion\":2,\"firstTime\":\"08:30\",\"secondTime\":\"20:00\"}")
                .andExpect(status().isOk());
        verify(useCase).changeSchedule("admin@example.com", ReminderSettingsCommand.schedule(2L, "08:30", "20:00", KEY));
        send(put("/notifications/settings/whatsapp/recipient"), "{\"expectedVersion\":0,\"phone\":\"(11) 98765-4321\"}")
                .andExpect(status().isOk());
        verify(useCase).changeRecipient("admin@example.com", ReminderSettingsCommand.recipient(0L, "(11) 98765-4321", KEY));
        send(post("/notifications/settings/whatsapp/consent"), "{\"expectedVersion\":1,\"phone\":\"11987654321\",\"accepted\":true}")
                .andExpect(status().isOk());
        verify(useCase).grantConsent("admin@example.com", ReminderSettingsCommand.consent(1L, "11987654321", true, KEY));
        send(post("/notifications/settings/whatsapp/consent/revocation"), "{\"expectedVersion\":2}")
                .andExpect(status().isOk());
        verify(useCase).revokeConsent("admin@example.com", ReminderSettingsCommand.revocation(2L, KEY));
        send(put("/notifications/settings/whatsapp/channel"), "{\"expectedVersion\":3,\"enabled\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        verify(useCase).changeChannel("admin@example.com", ReminderSettingsCommand.channel(3L, false, KEY));
        mvc.perform(get("/notifications/settings/events").principal(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].detail").value("nenhum -> +55 ** *****-4321"));
    }

    @Test
    void emptyBodiesReachTheUseCaseAsMissingValuesAndNullBodiesAreRejected() throws Exception {
        when(useCase.changeChannel(any(), any())).thenReturn(view());
        send(put("/notifications/settings/whatsapp/channel"), "{}").andExpect(status().isOk());
        verify(useCase).changeChannel("admin@example.com", ReminderSettingsCommand.channel(null, null, KEY));
        when(useCase.changeSchedule(any(), any())).thenReturn(view());
        send(put("/notifications/settings/schedule"), "{}").andExpect(status().isOk());
        verify(useCase).changeSchedule("admin@example.com", ReminderSettingsCommand.schedule(null, null, null, KEY));
        send(put("/notifications/settings/whatsapp/recipient"), "null").andExpect(status().isBadRequest());
        send(post("/notifications/settings/whatsapp/consent"), "null").andExpect(status().isBadRequest());
        send(post("/notifications/settings/whatsapp/consent/revocation"), "null").andExpect(status().isBadRequest());
    }

    @Test
    void mapsErrors() throws Exception {
        when(useCase.changeRecipient(any(), any())).thenThrow(new NotificationValidationException(
                WhatsAppRecipient.INVALID, "phone", "Informe um celular"));
        send(put("/notifications/settings/whatsapp/recipient"), "{\"expectedVersion\":0,\"phone\":\"1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WHATSAPP_RECIPIENT_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("phone"))
                .andExpect(jsonPath("$.operationId").exists());
        when(useCase.changeSchedule(any(), any())).thenThrow(new NotificationValidationException(
                ReminderSchedule.INVALID, "secondTime", "posterior"));
        send(put("/notifications/settings/schedule"), "{}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REMINDER_SCHEDULE_INVALID"));
        when(useCase.grantConsent(any(), any())).thenThrow(new WhatsAppRecipientMismatchException());
        send(post("/notifications/settings/whatsapp/consent"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WHATSAPP_RECIPIENT_MISMATCH"));
        when(useCase.revokeConsent(any(), any())).thenThrow(new WhatsAppConsentNotActiveException());
        send(post("/notifications/settings/whatsapp/consent/revocation"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WHATSAPP_CONSENT_NOT_ACTIVE"));
        when(useCase.changeChannel(any(), any())).thenThrow(new ReminderSettingsVersionConflictException());
        send(put("/notifications/settings/whatsapp/channel"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_SETTINGS_VERSION_CONFLICT"));
        when(useCase.events(any())).thenThrow(new NotificationAdministratorRequiredException());
        mvc.perform(get("/notifications/settings/events").principal(ADMIN)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_ADMINISTRATOR_REQUIRED"));
        when(useCase.view(any())).thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/notifications/settings").principal(ADMIN)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void mapsActivationAndIdempotencyErrors() throws Exception {
        when(useCase.changeChannel(eq("admin@example.com"), any())).thenThrow(new com.malyah.accountmanager.notifications.application.WhatsAppActivationRequiredException("WHATSAPP_CONSENT_REQUIRED", "Autorize antes de ativar."));
        send(put("/notifications/settings/whatsapp/channel"), "{\"expectedVersion\":1,\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WHATSAPP_CONSENT_REQUIRED"));
        when(useCase.grantConsent(any(), any())).thenThrow(new ReminderSettingsIdempotencyConflictException());
        send(post("/notifications/settings/whatsapp/consent"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void malformedRequestsNeverReachTheUseCase() throws Exception {
        mvc.perform(put("/notifications/settings/schedule").principal(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REMINDER_SETTINGS_INVALID"));
        mvc.perform(put("/notifications/settings/schedule").principal(ADMIN).header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        send(put("/notifications/settings/schedule"), "{\"expectedVersion\":\"x\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    private org.springframework.test.web.servlet.ResultActions send(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        return mvc.perform(request.principal(ADMIN).header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
