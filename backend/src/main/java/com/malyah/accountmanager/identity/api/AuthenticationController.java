package com.malyah.accountmanager.identity.api;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;
import com.malyah.accountmanager.identity.infrastructure.security.SessionLifetimeFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthenticationController {

    private static final String GENERIC_EMAIL_RESPONSE =
            "Se a conta estiver disponível para esta ação, enviaremos as instruções por email.";

    private final LoginUseCase loginUseCase;
    private final AccountAccessUseCase accountAccessUseCase;
    private final SessionRevoker sessionRevoker;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;

    AuthenticationController(
            LoginUseCase loginUseCase,
            AccountAccessUseCase accountAccessUseCase,
            SessionRevoker sessionRevoker,
            SecurityContextRepository securityContextRepository,
            Clock clock) {
        this.loginUseCase = loginUseCase;
        this.accountAccessUseCase = accountAccessUseCase;
        this.sessionRevoker = sessionRevoker;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return Map.of("headerName", csrfToken.getHeaderName());
    }

    @PostMapping("/login")
    ResponseEntity<Void> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var normalizedEmail = loginUseCase.authenticate(body.email(), body.password().toCharArray());
        var session = request.getSession(true);
        if (!session.isNew()) {
            request.changeSessionId();
        }
        SessionLifetimeFilter.initialize(session, clock.instant());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                normalizedEmail,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    ResponseEntity<Void> logoutEverywhere(HttpServletRequest request) {
        var principal = SecurityContextHolder.getContext().getAuthentication().getName();
        sessionRevoker.revokeAll(principal);
        var session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // A exclusão JDBC pode invalidar a sessão antes do container local.
            }
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email-confirmations")
    ResponseEntity<Map<String, String>> requestConfirmation(@Valid @RequestBody EmailAccessRequest body) {
        accountAccessUseCase.requestEmailConfirmation(body.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("message", GENERIC_EMAIL_RESPONSE));
    }

    @PostMapping("/email-confirmations/confirm")
    ResponseEntity<Void> confirmEmail(@Valid @RequestBody AccessTokenRequest body) {
        accountAccessUseCase.confirmEmail(body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-resets")
    ResponseEntity<Map<String, String>> requestPasswordReset(@Valid @RequestBody EmailAccessRequest body) {
        accountAccessUseCase.requestPasswordReset(body.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("message", GENERIC_EMAIL_RESPONSE));
    }

    @PostMapping("/password-resets/complete")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest body) {
        accountAccessUseCase.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
