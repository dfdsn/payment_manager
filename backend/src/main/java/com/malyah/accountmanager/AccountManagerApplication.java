package com.malyah.accountmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AccountManagerApplication {

    public static void main(String[] args) {
        if ("migrate".equalsIgnoreCase(System.getenv("APP_MODE"))) {
            System.setProperty("spring.main.web-application-type", "none");
            System.setProperty("spring.flyway.enabled", "true");
            try (var context = SpringApplication.run(AccountManagerApplication.class, args)) {
                // Fechar após o Flyway concluir impede que o migrador execute HTTP/jobs.
            }
            return;
        }
        SpringApplication.run(AccountManagerApplication.class, args);
    }
}
