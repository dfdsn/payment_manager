package com.malyah.accountmanager.identity.api;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.identity.application.ManagedMember;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;

import jakarta.servlet.http.HttpServletRequest;

@RestController
class MembershipController {
    private final MembershipManagementUseCase useCase;

    MembershipController(MembershipManagementUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/identity/members")
    List<ManagedMember> members(Principal principal) {
        return useCase.members(principal.getName());
    }

    @DeleteMapping("/identity/members/{userId}")
    ResponseEntity<Void> remove(Principal principal, @PathVariable UUID userId) {
        useCase.remove(principal.getName(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/identity/members/{userId}/administration-transfer")
    ResponseEntity<Void> transfer(Principal principal, @PathVariable UUID userId) {
        useCase.transferAdministration(principal.getName(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/identity/membership/leave")
    ResponseEntity<Void> leave(Principal principal, HttpServletRequest request) {
        useCase.leave(principal.getName());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
