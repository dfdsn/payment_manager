package com.malyah.accountmanager.notifications.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryView;

/**
 * H08.2: simulation of a slot and a generated summary (target of the authenticated link). Both members read; the
 * space always comes from the session. Nothing here writes, materializes or sends.
 */
@RestController
@RequestMapping("/notifications/reminders")
@ConditionalOnProperty(name = "spring.datasource.url")
class ReminderSummaryController {
    private final ReminderSummaryUseCase useCase;

    ReminderSummaryController(ReminderSummaryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/preview")
    ReminderSummaryView.Preview preview(Principal principal, @RequestParam(required = false) String date,
            @RequestParam(required = false) String slot) {
        return useCase.preview(principal.getName(), date, slot);
    }

    @GetMapping("/summaries/{id}")
    ReminderSummaryView summary(Principal principal, @PathVariable UUID id) {
        return useCase.summary(principal.getName(), id);
    }
}
