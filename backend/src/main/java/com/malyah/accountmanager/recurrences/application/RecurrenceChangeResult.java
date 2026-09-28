package com.malyah.accountmanager.recurrences.application;

public record RecurrenceChangeResult(RecurrenceView recurrence, RecurrenceChangeView change, boolean replayed) { }
