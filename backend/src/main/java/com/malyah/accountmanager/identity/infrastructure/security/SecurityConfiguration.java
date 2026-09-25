package com.malyah.accountmanager.identity.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            SessionLifetimeFilter sessionLifetimeFilter,
            SecurityContextRepository securityContextRepository) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        var production = "production".equalsIgnoreCase(environment.getProperty("APP_ENVIRONMENT", "local"));
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(production));

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/setup/status", "/actuator/health", "/auth/csrf", "/invitations/preview").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/setup", "/auth/login", "/auth/email-confirmations",
                                "/auth/email-confirmations/confirm", "/auth/password-resets",
                                "/auth/password-resets/complete", "/invitations/accept").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .addFilterAfter(sessionLifetimeFilter, SecurityContextHolderFilter.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\","
                            + "\"message\":\"Entre para continuar.\",\"fieldErrors\":[],"
                            + "\"operationId\":\"" + UUID.randomUUID() + "\"}");
                }))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionLifetimeFilter sessionLifetimeFilter(java.time.Clock clock) {
        return new SessionLifetimeFilter(clock);
    }
}
