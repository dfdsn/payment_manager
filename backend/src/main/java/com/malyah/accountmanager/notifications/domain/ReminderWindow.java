package com.malyah.accountmanager.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * RF-ALT-06/17/18: a slot is processed from its scheduled instant until one hour later or the next slot, whichever
 * comes first. After that it is missed, never sent late or accumulated.
 */
public record ReminderWindow(LocalDate date, ReminderSlot slot, Instant scheduledAt, Instant deadline) {
    public static final Duration MAXIMUM_DELAY = Duration.ofHours(1);

    public static ReminderWindow of(ReminderSchedule schedule, ZoneId zone, LocalDate date, ReminderSlot slot) {
        var scheduledAt = date.atTime(schedule.at(slot)).atZone(zone).toInstant();
        var next = slot == ReminderSlot.FIRST
                ? date.atTime(schedule.second()).atZone(zone).toInstant()
                : date.plusDays(1).atTime(schedule.first()).atZone(zone).toInstant();
        var limit = scheduledAt.plus(MAXIMUM_DELAY);
        return new ReminderWindow(date, slot, scheduledAt, next.isBefore(limit) ? next : limit);
    }

    public boolean started(Instant now) {
        return !now.isBefore(scheduledAt);
    }

    public boolean open(Instant now) {
        return started(now) && now.isBefore(deadline);
    }
}
