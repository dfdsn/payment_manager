package com.malyah.accountmanager.notifications.application;

/** H08.1: reminder times and the administrator's WhatsApp channel of the authenticated member's space. */
public interface ReminderSettingsUseCase {
    ReminderSettingsView view(String actorEmail);

    ReminderSettingsView.EventList events(String actorEmail);

    ReminderSettingsView changeSchedule(String actorEmail, ReminderSettingsCommand command);

    ReminderSettingsView changeRecipient(String actorEmail, ReminderSettingsCommand command);

    ReminderSettingsView grantConsent(String actorEmail, ReminderSettingsCommand command);

    ReminderSettingsView revokeConsent(String actorEmail, ReminderSettingsCommand command);

    ReminderSettingsView changeChannel(String actorEmail, ReminderSettingsCommand command);
}
