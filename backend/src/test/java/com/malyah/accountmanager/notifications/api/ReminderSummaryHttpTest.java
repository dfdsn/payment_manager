package com.malyah.accountmanager.notifications.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderQueryValidationException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryView;

/** H08.2 HTTP contract: preview and summary routes, decimal strings and error codes. */
class ReminderSummaryHttpTest {
    static final Principal GUEST = () -> "guest@example.com";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private ReminderSummaryUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ReminderSummaryUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReminderSummaryController(useCase))
                .setControllerAdvice(new ReminderSummaryExceptionHandler()).build();
    }

    static ReminderSummaryView summary(UUID id) {
        return new ReminderSummaryView(id, LocalDate.of(2026, 10, 5), "FIRST", "09:00", "America/Sao_Paulo",
                id == null ? null : Instant.parse("2026-10-05T12:00:05Z"), 7, "1349.91", 1, "99.99", 1, 5, 2,
                List.of(new ReminderSummaryView.Item(1, UUID.randomUUID(), null, "Aluguel", "Aluguel", "1500.00",
                        LocalDate.of(2026, 10, 1), false, true, false, "ONE_OFF", null, null)),
                id == null ? null : "https://contas.example/lembretes/resumos/" + id, "Contas a pagar",
                List.of(new ReminderSummaryView.ChannelView("IN_APP", "PLANNED", null),
                        new ReminderSummaryView.ChannelView("WHATSAPP", "SKIPPED", "PROVIDER_UNAVAILABLE")));
    }

    @Test
    void previewForAnyMember() throws Exception {
        when(useCase.preview("guest@example.com", "2026-10-05", "FIRST")).thenReturn(new ReminderSummaryView.Preview(
                LocalDate.of(2026, 10, 5), "FIRST", "09:00", "America/Sao_Paulo", LocalDate.of(2026, 10, 5), summary(null)));
        mvc.perform(get("/notifications/reminders/preview").param("date", "2026-10-05").param("slot", "FIRST").principal(GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value("FIRST"))
                .andExpect(jsonPath("$.summary.count").value(7))
                .andExpect(jsonPath("$.summary.total").value("1349.91"))
                .andExpect(jsonPath("$.summary.remaining").value(2))
                .andExpect(jsonPath("$.summary.items[0].overdue").value(true))
                .andExpect(jsonPath("$.summary.channels[1].reason").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.summary.id").doesNotExist());
    }

    @Test
    void emptyPreviewHasNoSummary() throws Exception {
        when(useCase.preview("guest@example.com", null, null)).thenReturn(new ReminderSummaryView.Preview(
                LocalDate.of(2026, 10, 5), "SECOND", "18:00", "America/Sao_Paulo", LocalDate.of(2026, 10, 5), null));
        mvc.perform(get("/notifications/reminders/preview").principal(GUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").doesNotExist());
    }

    @Test
    void summaryByLink() throws Exception {
        when(useCase.summary("guest@example.com", ID)).thenReturn(summary(ID));
        mvc.perform(get("/notifications/reminders/summaries/" + ID).principal(GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.link").value("https://contas.example/lembretes/resumos/" + ID));
    }

    @Test
    void errors() throws Exception {
        when(useCase.preview("guest@example.com", "x", "FIRST"))
                .thenThrow(new ReminderQueryValidationException("date", "Use a data no formato AAAA-MM-DD."));
        mvc.perform(get("/notifications/reminders/preview").param("date", "x").param("slot", "FIRST").principal(GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REMINDER_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"));
        mvc.perform(get("/notifications/reminders/summaries/nao-e-uuid").principal(GUEST))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
        when(useCase.summary("guest@example.com", ID)).thenThrow(new ReminderSummaryNotFoundException());
        mvc.perform(get("/notifications/reminders/summaries/" + ID).principal(GUEST))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REMINDER_SUMMARY_NOT_FOUND"));
        var other = UUID.randomUUID();
        when(useCase.summary("guest@example.com", other)).thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/notifications/reminders/summaries/" + other).principal(GUEST))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }
}
