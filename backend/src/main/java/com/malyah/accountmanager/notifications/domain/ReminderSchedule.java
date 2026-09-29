package com.malyah.accountmanager.notifications.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * RF-ALT-05: the two daily reminder times of the space, in the space time zone. Defaults 09:00 and 18:00; the second
 * must be later than the first on the same day. Minute resolution.
 */
public record ReminderSchedule(LocalTime first, LocalTime second) {
    public static final ReminderSchedule DEFAULT = new ReminderSchedule(LocalTime.of(9, 0), LocalTime.of(18, 0));
    public static final String INVALID = "REMINDER_SCHEDULE_INVALID";
    public static final String INVALID_FORMAT = "REMINDER_SCHEDULE_INVALID_FORMAT";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public ReminderSchedule {
        if (first == null || second == null)
            throw new NotificationValidationException(INVALID_FORMAT, "firstTime", "Informe os dois horários.");
        if (first.getSecond() != 0 || first.getNano() != 0 || second.getSecond() != 0 || second.getNano() != 0)
            throw new NotificationValidationException(INVALID_FORMAT, "firstTime", "Use horários em minutos (HH:mm).");
        if (!second.isAfter(first))
            throw new NotificationValidationException(INVALID, "secondTime",
                    "O segundo horário precisa ser posterior ao primeiro no mesmo dia.");
    }

    public static ReminderSchedule parse(String first, String second) {
        return new ReminderSchedule(time(first, "firstTime"), time(second, "secondTime"));
    }

    public LocalTime at(ReminderSlot slot) {
        return slot == ReminderSlot.FIRST ? first : second;
    }

    public String text() {
        return format(first) + "/" + format(second);
    }

    public static String format(LocalTime time) {
        return FORMAT.format(time);
    }

    private static LocalTime time(String value, String field) {
        if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new NotificationValidationException(INVALID_FORMAT, field, "Use o formato HH:mm, de 00:00 a 23:59.");
        return LocalTime.parse(value, FORMAT);
    }
}
