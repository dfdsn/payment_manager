package com.malyah.accountmanager.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvalidInvitationTokenException;
import com.malyah.accountmanager.identity.application.InvitationIdentityMismatchException;
import com.malyah.accountmanager.identity.application.InvitationPreview;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class InvitationHttpTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private InvitationUseCase invitationUseCase;
    @MockitoBean private LoginUseCase loginUseCase;
    @MockitoBean private AccountAccessUseCase accountAccessUseCase;
    @MockitoBean private SessionRevoker sessionRevoker;
    @MockitoBean private MembershipManagementUseCase membershipManagementUseCase;
    @MockitoBean private ExpenseUseCase expenseUseCase;
    @MockitoBean private InitialSetupUseCase initialSetupUseCase;
    @MockitoBean private AuthenticatedUserContextQuery contextQuery;

    @Test
    void protectsInvitationManagementAndAcceptsOnlyWithCsrf() throws Exception {
        mockMvc.perform(get("/identity/invitations"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/identity/invitations")
                        .with(user("admin@example.com"))
                        .session(activeSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/identity/invitations")
                        .with(user("admin@example.com"))
                        .session(activeSession())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isCreated());
        then(invitationUseCase).should().invite("admin@example.com", "guest@example.com");
    }

    @Test
    void previewsPublicInvitationWithoutDisclosingTokenInResponse() throws Exception {
        given(invitationUseCase.preview("opaque", null))
                .willReturn(new InvitationPreview("Casa", false, false, false));

        mockMvc.perform(get("/invitations/preview").param("token", "opaque"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitedEmail").doesNotExist())
                .andExpect(jsonPath("$.spaceName").value("Casa"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void mapsInvalidAndWrongIdentityAcceptanceWithoutGrantingAccess() throws Exception {
        willThrow(new InvalidInvitationTokenException()).given(invitationUseCase).accept(any());
        mockMvc.perform(post("/invitations/accept")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad\",\"displayName\":\"Pessoa\",\"password\":\"senha segura 2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));

        willThrow(new InvitationIdentityMismatchException()).given(invitationUseCase).accept(any());
        mockMvc.perform(post("/invitations/accept")
                        .with(user("other@example.com"))
                        .session(activeSession())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"valid\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_IDENTITY_MISMATCH"));
    }

    @Test
    void representsNoCurrentInvitationWithoutNullPrincipalAssumptions() throws Exception {
        given(invitationUseCase.current("admin@example.com")).willReturn(Optional.empty());
        mockMvc.perform(get("/identity/invitations")
                        .with(user("admin@example.com"))
                        .session(activeSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false));
    }

    private MockHttpSession activeSession() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, Instant.now());
        return session;
    }
}
