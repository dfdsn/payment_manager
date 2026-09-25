package com.malyah.accountmanager.expenses.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseCreationResult;
import com.malyah.accountmanager.expenses.application.ExpenseIdempotencyConflictException;
import com.malyah.accountmanager.expenses.application.ExpensePage;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class ExpenseHttpTest {
    private static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Autowired private MockMvc mvc;
    @MockitoBean private ExpenseUseCase useCase;
    @MockitoBean private LoginUseCase loginUseCase;
    @MockitoBean private AccountAccessUseCase accountAccessUseCase;
    @MockitoBean private SessionRevoker sessionRevoker;
    @MockitoBean private InitialSetupUseCase initialSetupUseCase;
    @MockitoBean private AuthenticatedUserContextQuery contextQuery;
    @MockitoBean private InvitationUseCase invitationUseCase;
    @MockitoBean private MembershipManagementUseCase membershipManagementUseCase;

    @Test
    void protectsCreateAndListWithSessionAndCsrf() throws Exception {
        mvc.perform(get("/expenses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/expenses").with(user("member@example.com")).session(activeSession())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(validPendingBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsAndReplaysWithExplicitHttpSemantics() throws Exception {
        given(useCase.create(any(), any())).willReturn(new ExpenseCreationResult(view(), false));
        mvc.perform(post("/expenses").with(user("member@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(validPendingBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/expenses/" + EXPENSE))
                .andExpect(jsonPath("$.amount").value("150.00"))
                .andExpect(jsonPath("$.overdue").value(true));
        then(useCase).should().create(org.mockito.ArgumentMatchers.eq("member@example.com"),
                any(CreateOneOffExpenseCommand.class));

        given(useCase.create(any(), any())).willReturn(new ExpenseCreationResult(view(), true));
        mvc.perform(post("/expenses").with(user("member@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(validPendingBody()))
                .andExpect(status().isOk());
    }

    @Test
    void validatesInputKeyAndMapsIdempotencyConflict() throws Exception {
        mvc.perform(post("/expenses").with(user("member@example.com")).session(activeSession()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(validPendingBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        willThrow(new ExpenseIdempotencyConflictException()).given(useCase).create(any(), any());
        mvc.perform(post("/expenses").with(user("member@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(validPendingBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void listsPageAndDeniesAuthenticatedUserWithoutActiveSpace() throws Exception {
        given(useCase.list(any(), any())).willReturn(new ExpensePage(
                List.of(view()), 0, 20, 1, 1, ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
        mvc.perform(get("/expenses").with(user("guest@example.com")).session(activeSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Energia"))
                .andExpect(jsonPath("$.totalElements").value(1));

        willThrow(new AuthenticatedUserContextNotFoundException()).given(useCase).list(any(), any());
        mvc.perform(get("/expenses").with(user("removed@example.com")).session(activeSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    private String validPendingBody() {
        return """
                {"description":"Energia","amount":"150.00","status":"PENDING","dueDate":"2026-09-24"}
                """;
    }

    @Test void paymentRequiresSessionCsrfAndValidDataAndMapsConflicts() throws Exception {
        var path = "/expenses/" + EXPENSE + "/payment";
        var body = "{\"version\":0,\"paidAmount\":\"155.00\",\"paymentDate\":\"2026-10-01\",\"paidByUserId\":\"" + KEY + "\"}";
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        given(useCase.settle(any(), any())).willReturn(new ExpenseCreationResult(view(), false));
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        then(useCase).should().settle(org.mockito.ArgumentMatchers.eq("guest@example.com"), org.mockito.ArgumentMatchers.argThat(
                command -> command.expenseId().equals(EXPENSE) && command.paidByUserId().equals(KEY) && command.version() == 0));
        willThrow(new com.malyah.accountmanager.expenses.application.ExpenseStateConflictException()).given(useCase).settle(any(), any());
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPENSE_STATE_CONFLICT"));
        willThrow(new com.malyah.accountmanager.expenses.application.ExpenseNotFoundException()).given(useCase).settle(any(), any());
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        willThrow(new AuthenticatedUserContextNotFoundException()).given(useCase).settle(any(), any());
        mvc.perform(post(path).with(user("guest@example.com")).session(activeSession()).with(csrf())
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    private ExpenseView view() {
        return new ExpenseView(EXPENSE, "ONE_OFF", "Energia", "150.00", "BRL", ExpenseStatus.PENDING,
                LocalDate.of(2026, 9, 24), null, null, LocalDate.of(2026, 9, 24), true,
                null, null, null, UUID.randomUUID(), "Pessoa", null, null,
                Instant.parse("2026-09-25T13:00:00Z"), 0);
    }

    private MockHttpSession activeSession() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, Instant.now());
        return session;
    }
}
