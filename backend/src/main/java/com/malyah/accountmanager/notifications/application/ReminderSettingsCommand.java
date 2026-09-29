package com.malyah.accountmanager.notifications.application;

import java.util.UUID;

/**
 * H08.1 changes. Every one carries the settings version the administrator saw and an idempotency key; the other
 * fields depend on the operation (times for the schedule, phone for recipient and consent, flags for consent
 * acceptance and activation).
 */
public record ReminderSettingsCommand(Long expectedVersion, String firstTime, String secondTime, String phone,
        Boolean flag, UUID idempotencyKey) {

    public static ReminderSettingsCommand schedule(Long expectedVersion, String first, String second, UUID key) {
        return new ReminderSettingsCommand(expectedVersion, first, second, null, null, key);
    }

    public static ReminderSettingsCommand recipient(Long expectedVersion, String phone, UUID key) {
        return new ReminderSettingsCommand(expectedVersion, null, null, phone, null, key);
    }

    public static ReminderSettingsCommand consent(Long expectedVersion, String phone, Boolean accepted, UUID key) {
        return new ReminderSettingsCommand(expectedVersion, null, null, phone, accepted, key);
    }

    public static ReminderSettingsCommand revocation(Long expectedVersion, UUID key) {
        return new ReminderSettingsCommand(expectedVersion, null, null, null, null, key);
    }

    public static ReminderSettingsCommand channel(Long expectedVersion, Boolean enabled, UUID key) {
        return new ReminderSettingsCommand(expectedVersion, null, null, null, enabled, key);
    }
}
