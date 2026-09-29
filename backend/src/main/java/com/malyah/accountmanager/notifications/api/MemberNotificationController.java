package com.malyah.accountmanager.notifications.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.MemberNotificationView;

/**
 * H08.3: each member's in-app notifications. The space and the recipient always come from the session; the
 * mutations only change the reader's own notification and require the CSRF token like every other one.
 */
@RestController
@RequestMapping("/notifications/inbox")
@ConditionalOnProperty(name = "spring.datasource.url")
class MemberNotificationController {
    private final MemberNotificationUseCase useCase;

    MemberNotificationController(MemberNotificationUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    MemberNotificationView.Page list(Principal principal, @RequestParam(required = false) String view,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return useCase.list(principal.getName(), view, page, size);
    }

    @GetMapping("/unread-count")
    MemberNotificationView.UnreadCount unreadCount(Principal principal) {
        return useCase.unreadCount(principal.getName());
    }

    @PostMapping("/{id}/read")
    MemberNotificationView read(Principal principal, @PathVariable UUID id) {
        return useCase.read(principal.getName(), id);
    }

    @PostMapping("/{id}/dismiss")
    MemberNotificationView dismiss(Principal principal, @PathVariable UUID id) {
        return useCase.dismiss(principal.getName(), id);
    }
}
