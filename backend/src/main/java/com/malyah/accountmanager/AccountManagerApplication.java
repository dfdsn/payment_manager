package com.malyah.accountmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AccountManagerApplication {

    public static void main(String[] args) {
        if ("migrate".equalsIgnoreCase(System.getenv("APP_MODE"))) {
            System.setProperty("spring.main.web-application-type", "none");
            System.setProperty("spring.flyway.enabled", "true");
            System.setProperty("app.jobs.recurrence.enabled", "false");
            try (var context = SpringApplication.run(AccountManagerApplication.class, args)) {
                // Fechar após o Flyway concluir impede que o migrador execute HTTP/jobs.
            }
            return;
        }
        SpringApplication.run(AccountManagerApplication.class, args);
    }
}
