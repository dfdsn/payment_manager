package com.malyah.accountmanager.notifications.application;

import java.util.UUID;

/** H08.2 reads for both members: the simulation of a slot and a generated summary (target of the link). */
public interface ReminderSummaryUseCase {
    ReminderSummaryView.Preview preview(String actorEmail, String date, String slot);

    ReminderSummaryView summary(String actorEmail, UUID summaryId);
}
