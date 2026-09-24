package com.malyah.accountmanager.identity.api;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.identity.application.InitialSetupCommand;
import com.malyah.accountmanager.identity.application.InitialSetupResult;
import com.malyah.accountmanager.identity.application.InitialSetupStatus;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/setup")
class InitialSetupController {

    private final InitialSetupUseCase useCase;

    InitialSetupController(InitialSetupUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/status")
    SetupStatusResponse status(CsrfToken csrfToken) {
        csrfToken.getToken();
        return new SetupStatusResponse(useCase.status());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InitialSetupResult configure(
            @RequestHeader(name = "X-Setup-Secret", required = false) String setupSecret,
            @Valid @RequestBody InitialSetupRequest request) {
        var password = request.password().toCharArray();
        try {
            return useCase.configure(new InitialSetupCommand(
                    setupSecret,
                    request.administratorName(),
                    request.email(),
                    password,
                    request.spaceName()));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    record SetupStatusResponse(InitialSetupStatus status) {
    }
}
