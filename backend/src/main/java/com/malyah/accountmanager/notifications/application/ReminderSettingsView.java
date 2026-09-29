package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.List;

/**
 * H08.1 view. {@code recipient} (full number) is only present for the administrator; the guest sees whether a
 * number exists and its last four digits. There is no field for provider credentials.
 */
public record ReminderSettingsView(boolean canManage, String timeZone, long version, Instant updatedAt,
        Schedule schedule, WhatsApp whatsapp) {

    public record Schedule(String firstTime, String secondTime, String defaultFirstTime, String defaultSecondTime) { }

    public record WhatsApp(boolean hasRecipient, String recipient, String recipientFormatted, String recipientLastDigits,
            boolean enabled, Consent consent, Provider provider, String state, String consentTextVersion,
            String consentText) { }

    public record Consent(boolean active, Instant grantedAt, String grantedByDisplayName, String recipientLastDigits) { }

    public record Provider(boolean available, String code, String message) { }

    public record EventList(List<EventItem> items) { }

    public record EventItem(String type, String actorDisplayName, Instant occurredAt, long fromVersion,
            long toVersion, String detail) { }
}
