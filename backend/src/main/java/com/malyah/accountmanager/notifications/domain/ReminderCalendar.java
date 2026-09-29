package com.malyah.accountmanager.notifications.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * PRD 10.2 / RF-ALT-09: which pending bill each logical slot mentions, by local date of the space. The first slot
 * takes the overdue bills and those due from today up to five days ahead; the second only those due today and
 * tomorrow. Six or more days ahead, nothing.
 */
public final class ReminderCalendar {
    public static final int FIRST_SLOT_DAYS_AHEAD = 5;
    public static final int SECOND_SLOT_DAYS_AHEAD = 1;

    private ReminderCalendar() { }

    public static boolean eligible(ReminderSlot slot, LocalDate dueDate, LocalDate today) {
        Objects.requireNonNull(slot);
        var daysAhead = ChronoUnit.DAYS.between(today, dueDate);
        if (slot == ReminderSlot.FIRST) return daysAhead <= FIRST_SLOT_DAYS_AHEAD;
        return daysAhead >= 0 && daysAhead <= SECOND_SLOT_DAYS_AHEAD;
    }

    /** The last due date a slot of {@code today} can mention. */
    public static LocalDate lastDueDate(ReminderSlot slot, LocalDate today) {
        return today.plusDays(slot == ReminderSlot.FIRST ? FIRST_SLOT_DAYS_AHEAD : SECOND_SLOT_DAYS_AHEAD);
    }
}
