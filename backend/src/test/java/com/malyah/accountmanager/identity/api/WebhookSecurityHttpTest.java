package com.malyah.accountmanager.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;

/**
 * H08.4 security chain: only the webhook path is reachable without session and, for POST, without CSRF; any other
 * path and method keeps the rules. The endpoint here is a stand-in route on the same path (the real controller
 * needs the database); its authenticity checks are covered by WhatsAppDeliveryPostgresIT.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class WebhookSecurityHttpTest {
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
        RouterFunction<ServerResponse> standInWebhook() {
            return RouterFunctions.route()
                    .GET("/integrations/whatsapp/webhook", request -> ServerResponse.ok().body("verify"))
                    .POST("/integrations/whatsapp/webhook", request -> ServerResponse.ok().body("received"))
                    .PUT("/integrations/whatsapp/webhook", request -> ServerResponse.ok().body("put"))
                    .POST("/integrations/whatsapp/other", request -> ServerResponse.ok().body("other"))
                    .build();
        }
    }

    @Test
    void onlyTheWebhookSkipsSessionAndCsrf() throws Exception {
        mockMvc.perform(get("/integrations/whatsapp/webhook")).andExpect(status().isOk())
                .andExpect(content().string("verify"));
        mockMvc.perform(post("/integrations/whatsapp/webhook").content("{}")).andExpect(status().isOk())
                .andExpect(content().string("received"));
        mockMvc.perform(put("/integrations/whatsapp/webhook").content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/integrations/whatsapp/other").content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/notifications/inbox/00000000-0000-0000-0000-000000000001/read"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/notifications/inbox")).andExpect(status().isUnauthorized());
    }
}
