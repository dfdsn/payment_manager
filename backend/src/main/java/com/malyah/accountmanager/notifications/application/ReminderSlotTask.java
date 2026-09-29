package com.malyah.accountmanager.notifications.application;

import com.malyah.accountmanager.notifications.domain.ReminderWindow;

/** A slot the job must handle now: generate it inside its window, or record it as missed. */
public record ReminderSlotTask(SpaceReminderSchedule space, ReminderWindow window, Action action) {
    public enum Action { GENERATE, MISS }
}
