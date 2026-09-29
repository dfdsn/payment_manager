package com.malyah.accountmanager.notifications.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.malyah.accountmanager.notifications.application.MemberNotificationNotFoundException;
import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.MemberNotificationView;
import com.malyah.accountmanager.notifications.application.NotificationQueryValidationException;

/** H08.3 HTTP contract: routes, parameters and error codes (CSRF and 401 are exercised by the full-stack E2E). */
class MemberNotificationHttpTest {
    static final Principal GUEST = () -> "guest@example.com";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private MemberNotificationUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        useCase = mock(MemberNotificationUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new MemberNotificationController(useCase))
                .setControllerAdvice(new MemberNotificationExceptionHandler()).build();
    }

    static MemberNotificationView notice(Instant readAt) {
        return new MemberNotificationView(ID, "REMINDER_SUMMARY", "Contas a pagar: primeiro horário de 05/10, 09:00",
                "1 conta, total R$ 10,00", Instant.parse("2026-10-05T12:00:05Z"), readAt, null,
                new MemberNotificationView.Summary(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "FIRST", "09:00",
                        "America/Sao_Paulo", 1, "10.00", 0, "0.00", 0, 0, "https://contas.example/lembretes/resumos/x"),
                null);
    }

    @Test
    void listsWithTheQueryParameters() throws Exception {
        when(useCase.list("guest@example.com", "DISMISSED", 1, 5)).thenReturn(
                new MemberNotificationView.Page(List.of(notice(null)), 1, 5, 6, 2, 3, "DISMISSED"));
        mvc.perform(get("/notifications/inbox").param("view", "DISMISSED").param("page", "1").param("size", "5")
                        .principal(GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.items[0].summary.total").value("10.00"))
                .andExpect(jsonPath("$.items[0].failure").doesNotExist())
                .andExpect(jsonPath("$.totalItems").value(6))
                .andExpect(jsonPath("$.unreadCount").value(3));
        when(useCase.unreadCount("guest@example.com")).thenReturn(new MemberNotificationView.UnreadCount(2));
        mvc.perform(get("/notifications/inbox/unread-count").principal(GUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void readAndDismissReturnTheNotification() throws Exception {
        when(useCase.read("guest@example.com", ID)).thenReturn(notice(Instant.parse("2026-10-05T13:00:00Z")));
        mvc.perform(post("/notifications/inbox/{id}/read", ID).principal(GUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").value("2026-10-05T13:00:00Z"));
        when(useCase.dismiss("guest@example.com", ID)).thenReturn(notice(Instant.parse("2026-10-05T13:00:00Z")));
        mvc.perform(post("/notifications/inbox/{id}/dismiss", ID).principal(GUEST)).andExpect(status().isOk());
    }

    @Test
    void errorsHaveStableCodes() throws Exception {
        when(useCase.list("guest@example.com", null, null, 500))
                .thenThrow(new NotificationQueryValidationException("size", "Escolha de 1 a 100 avisos por página."));
        mvc.perform(get("/notifications/inbox").param("size", "500").principal(GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mvc.perform(get("/notifications/inbox").param("page", "um").principal(GUEST))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        mvc.perform(post("/notifications/inbox/not-a-uuid/read").principal(GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Identificador de aviso inválido."));
        when(useCase.dismiss("guest@example.com", ID)).thenThrow(new MemberNotificationNotFoundException());
        mvc.perform(post("/notifications/inbox/{id}/dismiss", ID).principal(GUEST))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        when(useCase.read("guest@example.com", ID)).thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(post("/notifications/inbox/{id}/read", ID).principal(GUEST))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void theMutationsAreOnlyPost() throws Exception {
        mvc.perform(get("/notifications/inbox/{id}/read", ID).principal(GUEST)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(useCase);
    }
}
