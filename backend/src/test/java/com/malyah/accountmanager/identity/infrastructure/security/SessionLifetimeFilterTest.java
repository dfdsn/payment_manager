package com.malyah.accountmanager.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionLifetimeFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-24T15:00:00Z");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAbsoluteLifetimeEvenWhenRecentActivityExists() throws Exception {
        var session = authenticatedSession();
        session.setAttribute(SessionLifetimeFilter.STARTED_AT, NOW.minusSeconds(30L * 86400).toEpochMilli());
        session.setAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT, NOW.minusSeconds(60).toEpochMilli());

        var response = execute(session, "GET", true);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SESSION_EXPIRED");
        assertThat(response.getContentAsString()).contains("operationId");
    }

    @Test
    void rejectsSevenDaysWithoutHumanActivity() throws Exception {
        var session = authenticatedSession();
        session.setAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT,
                NOW.minusSeconds(7L * 86400).toEpochMilli());

        assertThat(execute(session, "GET", false).getStatus()).isEqualTo(401);
    }

    @Test
    void backgroundGetDoesNotRenewHumanActivity() throws Exception {
        var session = authenticatedSession();
        var previous = NOW.minusSeconds(3600).toEpochMilli();
        session.setAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT, previous);

        assertThat(execute(session, "GET", false).getStatus()).isEqualTo(200);
        assertThat(session.getAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT)).isEqualTo(previous);
    }

    @Test
    void explicitHumanGetAndMutationRenewActivity() throws Exception {
        var getSession = authenticatedSession();
        getSession.setAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT, NOW.minusSeconds(60).toEpochMilli());
        assertThat(execute(getSession, "GET", true).getStatus()).isEqualTo(200);
        assertThat(getSession.getAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT))
                .isEqualTo(NOW.toEpochMilli());

        var postSession = authenticatedSession();
        postSession.setAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT, NOW.minusSeconds(60).toEpochMilli());
        assertThat(execute(postSession, "POST", false).getStatus()).isEqualTo(200);
        assertThat(postSession.getAttribute(SessionLifetimeFilter.LAST_HUMAN_ACTIVITY_AT))
                .isEqualTo(NOW.toEpochMilli());
    }

    private MockHttpSession authenticatedSession() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("admin@example.com", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var session = new MockHttpSession();
        SessionLifetimeFilter.initialize(session, NOW.minusSeconds(86400));
        return session;
    }

    private MockHttpServletResponse execute(MockHttpSession session, String method, boolean human) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/identity/me");
        request.setSession(session);
        if (human) {
            request.addHeader("X-User-Activity", "true");
        }
        var response = new MockHttpServletResponse();
        new SessionLifetimeFilter(Clock.fixed(NOW, ZoneOffset.UTC))
                .doFilter(request, response, new MockFilterChain());
        return response;
    }
}
