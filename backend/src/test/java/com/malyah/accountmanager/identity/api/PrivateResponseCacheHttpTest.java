package com.malyah.accountmanager.identity.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

/**
 * H09.1: the PWA caches only the app shell, and the second line of defence is the API itself. Every answer of the
 * security chain, authenticated or not, tells browsers, proxies and service workers not to store it. The routes are
 * stand-ins on real private paths (the real controllers need the database); the headers come from the chain.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class PrivateResponseCacheHttpTest {
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
    @MockitoBean
    private InvitationUseCase invitationUseCase;
    @MockitoBean
    private MembershipManagementUseCase membershipManagementUseCase;
    @MockitoBean
    private ExpenseUseCase expenseUseCase;

    @TestConfiguration
    static class StandIn {
        @Bean
        RouterFunction<ServerResponse> standInPrivateRoutes() {
            return RouterFunctions.route()
                    .GET("/reports/due-dashboard-stand-in", request -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON).body("{\"pendingTotal\":\"1234.56\"}"))
                    .GET("/notifications/inbox-stand-in", request -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON).body("{\"items\":[]}"))
                    .build();
        }
    }

    @Test
    void authenticatedFinancialAnswersAreNeverStored() throws Exception {
        notStored(mockMvc.perform(get("/reports/due-dashboard-stand-in").with(user("ana@example.com")).session(activeSession())))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"pendingTotal\":\"1234.56\"}"));
        notStored(mockMvc.perform(get("/notifications/inbox-stand-in").with(user("ana@example.com")).session(activeSession())))
                .andExpect(status().isOk());
        when(contextQuery.findByEmail("ana@example.com")).thenReturn(new AuthenticatedUserContext(
                UUID.randomUUID(), "Ana", "ana@example.com", UUID.randomUUID(), "Casa", SpaceRole.ADMINISTRATOR,
                "BRL", "pt-BR", "America/Sao_Paulo"));
        notStored(mockMvc.perform(get("/identity/me").with(user("ana@example.com")).session(activeSession())))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"displayName\":\"Ana\"}"));
    }

    @Test
    void refusalsWithoutSessionAreNotStoredEither() throws Exception {
        notStored(mockMvc.perform(get("/reports/due-dashboard-stand-in"))).andExpect(status().isUnauthorized());
        notStored(mockMvc.perform(get("/identity/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredSessionIsRefusedWithoutBeingStored() throws Exception {
        var expired = new MockHttpSession();
        SessionLifetimeFilter.initialize(expired, Instant.now().minus(Duration.ofDays(31)));
        notStored(mockMvc.perform(get("/reports/due-dashboard-stand-in").with(user("ana@example.com")).session(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"SESSION_EXPIRED\"}"));
        notStored(mockMvc.perform(get("/identity/me").with(user("ana@example.com"))))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpSession activeSession() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, Instant.now());
        return session;
    }

    private static ResultActions notStored(ResultActions result) throws Exception {
        return result
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-cache")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }
}
