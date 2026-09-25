package com.malyah.accountmanager.identity.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.ManagedMember;
import com.malyah.accountmanager.identity.application.ManagedMemberNotFoundException;
import com.malyah.accountmanager.identity.application.MembershipAdministratorRequiredException;
import com.malyah.accountmanager.identity.application.MembershipConflictException;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class MembershipHttpTest {
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired private MockMvc mvc;
    @MockitoBean private MembershipManagementUseCase useCase;
    @MockitoBean private LoginUseCase loginUseCase;
    @MockitoBean private AccountAccessUseCase accountAccessUseCase;
    @MockitoBean private SessionRevoker sessionRevoker;
    @MockitoBean private InitialSetupUseCase initialSetupUseCase;
    @MockitoBean private AuthenticatedUserContextQuery contextQuery;
    @MockitoBean private InvitationUseCase invitationUseCase;

    @Test
    void protectsMemberDataAndAdministrativeMutationsAtTheApi() throws Exception {
        mvc.perform(get("/identity/members")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/identity/members/{id}", GUEST)
                .with(user("admin@example.com")).session(activeSession()))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/identity/members/{id}", GUEST)
                .with(user("admin@example.com")).session(activeSession()).with(csrf()))
                .andExpect(status().isNoContent());
        then(useCase).should().remove("admin@example.com", GUEST);
    }

    @Test
    void listsRolesWithoutTrustingTheInterfaceForAuthorization() throws Exception {
        given(useCase.members("guest@example.com")).willReturn(List.of(
                new ManagedMember(GUEST, "Pessoa", "guest@example.com", SpaceRole.GUEST, true)));

        mvc.perform(get("/identity/members")
                .with(user("guest@example.com")).session(activeSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("GUEST"))
                .andExpect(jsonPath("$[0].currentUser").value(true));
    }

    @Test
    void mapsGuestPermissionMissingMemberAndConcurrentConflict() throws Exception {
        willThrow(new MembershipAdministratorRequiredException()).given(useCase)
                .remove("guest@example.com", GUEST);
        mvc.perform(delete("/identity/members/{id}", GUEST)
                .with(user("guest@example.com")).session(activeSession()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_ADMIN_REQUIRED"));

        willThrow(new ManagedMemberNotFoundException()).given(useCase)
                .transferAdministration("admin@example.com", GUEST);
        mvc.perform(post("/identity/members/{id}/administration-transfer", GUEST)
                .with(user("admin@example.com")).session(activeSession()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_MEMBER_NOT_FOUND"));

        willThrow(new MembershipConflictException("conflito")).given(useCase).leave("guest@example.com");
        mvc.perform(post("/identity/membership/leave")
                .with(user("guest@example.com")).session(activeSession()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_CONFLICT"));
    }

    @Test
    void successfulVoluntaryExitInvalidatesCurrentHttpSession() throws Exception {
        var session = activeSession();
        mvc.perform(post("/identity/membership/leave")
                .with(user("guest@example.com")).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        then(useCase).should().leave("guest@example.com");
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession activeSession() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, Instant.now());
        return session;
    }
}
