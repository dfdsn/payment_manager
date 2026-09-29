package com.malyah.accountmanager.notifications.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.notifications.application.ReminderSettingsCommand;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSettingsView;

/**
 * H08.1: reminder times and the administrator's WhatsApp channel. Both members read; only the administrator
 * changes. The space always comes from the session, never from the request, and no provider credential is exposed.
 */
@RestController
@RequestMapping("/notifications/settings")
@ConditionalOnProperty(name = "spring.datasource.url")
class ReminderSettingsController {
    private final ReminderSettingsUseCase useCase;

    ReminderSettingsController(ReminderSettingsUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    ReminderSettingsView view(Principal principal) {
        return useCase.view(principal.getName());
    }

    @GetMapping("/events")
    ReminderSettingsView.EventList events(Principal principal) {
        return useCase.events(principal.getName());
    }

    @PutMapping("/schedule")
    ReminderSettingsView schedule(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ScheduleRequest request) {
        return useCase.changeSchedule(principal.getName(), ReminderSettingsCommand.schedule(
                request.expectedVersion(), request.firstTime(),
                request.secondTime(), key));
    }

    @PutMapping("/whatsapp/recipient")
    ReminderSettingsView recipient(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RecipientRequest request) {
        return useCase.changeRecipient(principal.getName(), ReminderSettingsCommand.recipient(
                request.expectedVersion(), request.phone(), key));
    }

    @PostMapping("/whatsapp/consent")
    ReminderSettingsView consent(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ConsentRequest request) {
        return useCase.grantConsent(principal.getName(), ReminderSettingsCommand.consent(
                request.expectedVersion(), request.phone(),
                request.accepted(), key));
    }

    @PostMapping("/whatsapp/consent/revocation")
    ReminderSettingsView revoke(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody VersionRequest request) {
        return useCase.revokeConsent(principal.getName(), ReminderSettingsCommand.revocation(
                request.expectedVersion(), key));
    }

    @PutMapping("/whatsapp/channel")
    ReminderSettingsView channel(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ChannelRequest request) {
        return useCase.changeChannel(principal.getName(), ReminderSettingsCommand.channel(
                request.expectedVersion(), request.enabled(), key));
    }

    record ScheduleRequest(Long expectedVersion, String firstTime, String secondTime) { }

    record RecipientRequest(Long expectedVersion, String phone) { }

    record ConsentRequest(Long expectedVersion, String phone, Boolean accepted) { }

    record VersionRequest(Long expectedVersion) { }

    record ChannelRequest(Long expectedVersion, Boolean enabled) { }
}
