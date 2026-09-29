package com.malyah.accountmanager.notifications.application;

import java.time.ZoneId;
import java.util.UUID;

import com.malyah.accountmanager.notifications.domain.ReminderSchedule;

/** A space as the reminder job sees it: its time zone and current reminder times. */
public record SpaceReminderSchedule(UUID spaceId, ZoneId zone, ReminderSchedule schedule) { }
