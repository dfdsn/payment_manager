package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;
import com.malyah.accountmanager.notifications.domain.WhatsAppSuspensionReason;

/**
 * Reminder settings of a space as stored; version 0 means never written (defaults). H08.5: {@code suspension} is
 * set when a permanent failure turned the channel off; it lasts until the administrator enables the channel again
 * or saves another number.
 */
public record StoredReminderSettings(UUID spaceId, ReminderSchedule schedule, WhatsAppRecipient recipient,
        boolean enabled, long version, Instant updatedAt, WhatsAppSuspensionReason suspension, Instant suspendedAt) {

    public StoredReminderSettings(UUID spaceId, ReminderSchedule schedule, WhatsAppRecipient recipient,
            boolean enabled, long version, Instant updatedAt) {
        this(spaceId, schedule, recipient, enabled, version, updatedAt, null, null);
    }

    public static StoredReminderSettings defaults(UUID spaceId) {
        return new StoredReminderSettings(spaceId, ReminderSchedule.DEFAULT, null, false, 0, null);
    }

    public boolean suspended() {
        return suspension != null;
    }

    /** Enabling the channel or changing the number ends a suspension; any other change keeps it. */
    StoredReminderSettings next(ReminderSchedule newSchedule, WhatsAppRecipient newRecipient, boolean newEnabled,
            Instant at) {
        var keep = !newEnabled && Objects.equals(newRecipient, recipient);
        return new StoredReminderSettings(spaceId, newSchedule,newRecipient, newEnabled,
                version + 1, at, keep ? suspension : null, keep ? suspendedAt : null);
    }

    /** H08.5: the channel off because of a permanent failure; number and consent stay as they are. */
    StoredReminderSettings suspend(WhatsAppSuspensionReason reason, Instant at) {
        return new StoredReminderSettings(spaceId, schedule, recipient, false, version + 1, at,
                Objects.requireNonNull(reason), at);
    }
}
