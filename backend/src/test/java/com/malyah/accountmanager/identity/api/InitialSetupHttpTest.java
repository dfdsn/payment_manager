package com.malyah.accountmanager.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.BDDMockito.then;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpSession;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

import com.malyah.accountmanager.identity.application.InitialSetupResult;
import com.malyah.accountmanager.identity.application.InitialSetupStatus;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.SetupAlreadyCompletedException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class InitialSetupHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvitationUseCase invitationUseCase;

    @MockitoBean
    private InitialSetupUseCase useCase;

    @MockitoBean
    private AuthenticatedUserContextQuery contextQuery;

    @MockitoBean
    private AccountAccessUseCase accountAccessUseCase;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private SessionRevoker sessionRevoker;

    @MockitoBean
    private MembershipManagementUseCase membershipManagementUseCase;

    @Test
    void exposesStatusAndCsrfCookieWithoutAuthentication() throws Exception {
        given(useCase.status()).willReturn(InitialSetupStatus.AVAILABLE);

        mockMvc.perform(get("/setup/status"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void requiresCsrfAndTemporarySecretForMutation() throws Exception {
        mockMvc.perform(post("/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden());

        var csrf = csrfCookie();
        given(useCase.configure(any())).willReturn(result());
        mockMvc.perform(post("/setup")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .header("X-Setup-Secret", "temporario-seguro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.locale").value("pt-BR"))
                .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"));
    }

    @Test
    void validatesPayloadAndMapsPersistentClosureToConflict() throws Exception {
        var csrf = csrfCookie();
        mockMvc.perform(post("/setup")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .header("X-Setup-Secret", "temporario-seguro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4));

        given(useCase.configure(any())).willThrow(new SetupAlreadyCompletedException());
        mockMvc.perform(post("/setup")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .header("X-Setup-Secret", "temporario-seguro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
    }

    @Test
    void protectsEveryOtherEndpoint() throws Exception {
        mockMvc.perform(get("/identity/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesSpaceContextOnlyToAuthenticatedPrincipal() throws Exception {
        given(contextQuery.findByEmail("ADMIN@EXAMPLE.COM")).willReturn(context());

        mockMvc.perform(get("/identity/me")
                        .session(authenticatedSession())
                        .header("X-User-Activity", "true")
                        .with(user("ADMIN@EXAMPLE.COM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Administrador"))
                .andExpect(jsonPath("$.role").value("ADMINISTRATOR"))
                .andExpect(jsonPath("$.spaceName").value("Minha casa"))
                .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"));

        then(contextQuery).should().findByEmail("ADMIN@EXAMPLE.COM");
    }

    private String validRequest() {
        return """
                {
                  "administratorName": "Administrador",
                  "email": "admin@example.com",
                  "password": "frase segura 2026",
                  "spaceName": "Minha casa"
                }
                """;
    }

    private Cookie csrfCookie() throws Exception {
        given(useCase.status()).willReturn(InitialSetupStatus.AVAILABLE);
        return mockMvc.perform(get("/setup/status"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }

    private InitialSetupResult result() {
        return new InitialSetupResult(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Administrador",
                "admin@example.com",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Minha casa",
                "BRL",
                "pt-BR",
                "America/Sao_Paulo");
    }

    private AuthenticatedUserContext context() {
        return new AuthenticatedUserContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Administrador",
                "admin@example.com",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Minha casa",
                SpaceRole.ADMINISTRATOR,
                "BRL",
                "pt-BR",
                "America/Sao_Paulo");
    }

    private MockHttpSession authenticatedSession() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, java.time.Instant.now());
        return session;
    }
}
