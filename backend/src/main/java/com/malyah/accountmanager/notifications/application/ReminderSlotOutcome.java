package com.malyah.accountmanager.notifications.application;

/** What processing a slot did; {@code ALREADY_PROCESSED} means another worker or run had recorded it. */
public enum ReminderSlotOutcome { GENERATED, EMPTY, MISSED, ALREADY_PROCESSED }
