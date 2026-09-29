package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;

/** Reminder settings of a space as stored; version 0 means never written (defaults). */
public record StoredReminderSettings(UUID spaceId, ReminderSchedule schedule, WhatsAppRecipient recipient,
        boolean enabled, long version, Instant updatedAt) {

    public static StoredReminderSettings defaults(UUID spaceId) {
        return new StoredReminderSettings(spaceId, ReminderSchedule.DEFAULT, null, false, 0, null);
    }

    StoredReminderSettings next(ReminderSchedule newSchedule, WhatsAppRecipient newRecipient, boolean newEnabled,
            Instant at) {
        return new StoredReminderSettings(spaceId, newSchedule, newRecipient, newEnabled, version + 1, at);
    }
}
