package com.malyah.accountmanager.identity.api;

import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.identity.application.InvitationAcceptanceCommand;
import com.malyah.accountmanager.identity.application.InvitationPreview;
import com.malyah.accountmanager.identity.application.InvitationUseCase;

import jakarta.validation.Valid;

@RestController
class InvitationController {

    private final InvitationUseCase useCase;

    InvitationController(InvitationUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/identity/invitations")
    InvitationStateResponse current(Principal principal) {
        return useCase.current(principal.getName())
                .map(status -> new InvitationStateResponse(true, status.invitedEmail(), status.expiresAt()))
                .orElseGet(() -> new InvitationStateResponse(false, null, null));
    }

    @PostMapping("/identity/invitations")
    ResponseEntity<Void> invite(Principal principal, @Valid @RequestBody InvitationRequest body) {
        useCase.invite(principal.getName(), body.email());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/identity/invitations/resend")
    ResponseEntity<Void> resend(Principal principal) {
        useCase.resend(principal.getName());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/identity/invitations")
    ResponseEntity<Void> revoke(Principal principal) {
        useCase.revoke(principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations/preview")
    InvitationPreview preview(@RequestParam String token, Principal principal) {
        return useCase.preview(token, principal == null ? null : principal.getName());
    }

    @PostMapping("/invitations/accept")
    ResponseEntity<Void> accept(
            @Valid @RequestBody InvitationAcceptanceRequest body,
            Principal principal) {
        var password = body.password() == null ? null : body.password().toCharArray();
        try {
            useCase.accept(new InvitationAcceptanceCommand(
                    body.token(), principal == null ? null : principal.getName(), body.displayName(), password));
            return ResponseEntity.noContent().build();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    record InvitationStateResponse(boolean pending, String invitedEmail, Instant expiresAt) {
    }
}
