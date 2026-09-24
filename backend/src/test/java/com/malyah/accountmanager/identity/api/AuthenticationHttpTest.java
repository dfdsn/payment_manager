package com.malyah.accountmanager.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvalidCredentialsException;
import com.malyah.accountmanager.identity.application.InvalidOrExpiredAccessTokenException;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class AuthenticationHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private AccountAccessUseCase accountAccessUseCase;

    @MockitoBean
    private SessionRevoker sessionRevoker;

    @MockitoBean
    private InitialSetupUseCase initialSetupUseCase;

    @MockitoBean
    private AuthenticatedUserContextQuery contextQuery;

    @Test
    void logsInWithRotatedPersistentServerSessionAndLogsOut() throws Exception {
        given(loginUseCase.authenticate(any(), any())).willReturn("admin@example.com");
        var csrf = csrfCookie();

        var login = mockMvc.perform(post("/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ADMIN@example.com","password":"frase segura 2026"}
                                """))
                .andExpect(status().isNoContent())
                .andExpect(authenticated().withUsername("admin@example.com"))
                .andReturn();

        var session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(post("/auth/logout")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsInvalidCredentialsWithGenericResponse() throws Exception {
        given(loginUseCase.authenticate(any(), any())).willThrow(new InvalidCredentialsException());
        var csrf = csrfCookie();

        mockMvc.perform(post("/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"senha qualquer"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email ou senha inválidos."));
    }

    @Test
    void usesSameAcceptedResponseWithoutDisclosingAccountExistence() throws Exception {
        var csrf = csrfCookie();
        var body = "{\"email\":\"person@example.com\"}";

        mockMvc.perform(post("/auth/email-confirmations")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "Se a conta estiver disponível para esta ação, enviaremos as instruções por email."));
        mockMvc.perform(post("/auth/password-resets")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "Se a conta estiver disponível para esta ação, enviaremos as instruções por email."));
    }

    @Test
    void rejectsInvalidExpiredOrReusedTokenWithoutGrantingAccess() throws Exception {
        willThrow(new InvalidOrExpiredAccessTokenException())
                .given(accountAccessUseCase).confirmEmail("invalid");
        var csrf = csrfCookie();

        mockMvc.perform(post("/auth/email-confirmations/confirm")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    void revokesAllOwnSessionsOnlyWhenAuthenticated() throws Exception {
        var csrf = csrfCookie();
        mockMvc.perform(delete("/auth/sessions")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.operationId").isNotEmpty());

        given(loginUseCase.authenticate(any(), any())).willReturn("admin@example.com");
        var login = mockMvc.perform(post("/auth/login")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"senha\"}"))
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(delete("/auth/sessions")
                        .session(session)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        then(sessionRevoker).should().revokeAll("admin@example.com");
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
    }
}
