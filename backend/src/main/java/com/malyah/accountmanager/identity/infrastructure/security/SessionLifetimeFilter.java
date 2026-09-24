package com.malyah.accountmanager.identity.infrastructure.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public final class SessionLifetimeFilter extends OncePerRequestFilter {

    public static final String STARTED_AT = "accountManager.session.startedAt";
    public static final String LAST_HUMAN_ACTIVITY_AT = "accountManager.session.lastHumanActivityAt";
    static final Duration MAX_INACTIVITY = Duration.ofDays(7);
    static final Duration MAX_ABSOLUTE_LIFETIME = Duration.ofDays(30);

    private final Clock clock;

    public SessionLifetimeFilter(Clock clock) {
        this.clock = clock;
    }

    public static void initialize(HttpSession session, Instant now) {
        session.setAttribute(STARTED_AT, now.toEpochMilli());
        session.setAttribute(LAST_HUMAN_ACTIVITY_AT, now.toEpochMilli());
        session.setMaxInactiveInterval(Math.toIntExact(MAX_INACTIVITY.toSeconds()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }

        var session = request.getSession(false);
        if (session == null) {
            unauthorized(response, "A sessão não está mais disponível.");
            return;
        }
        var now = clock.instant();
        var startedAt = instantAttribute(session, STARTED_AT);
        var lastHumanActivity = instantAttribute(session, LAST_HUMAN_ACTIVITY_AT);
        if (startedAt == null || lastHumanActivity == null
                || !now.isBefore(startedAt.plus(MAX_ABSOLUTE_LIFETIME))
                || !now.isBefore(lastHumanActivity.plus(MAX_INACTIVITY))) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            unauthorized(response, "A sessão expirou. Entre novamente.");
            return;
        }

        if (isHumanActivity(request)) {
            session.setAttribute(LAST_HUMAN_ACTIVITY_AT, now.toEpochMilli());
        }
        filterChain.doFilter(request, response);
    }

    private boolean isHumanActivity(HttpServletRequest request) {
        var method = request.getMethod();
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method))
                || "true".equalsIgnoreCase(request.getHeader("X-User-Activity"));
    }

    private Instant instantAttribute(HttpSession session, String name) {
        var value = session.getAttribute(name);
        return value instanceof Long epochMilli ? Instant.ofEpochMilli(epochMilli) : null;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"" + message
                + "\",\"fieldErrors\":[],\"operationId\":\"" + UUID.randomUUID() + "\"}");
    }
}
