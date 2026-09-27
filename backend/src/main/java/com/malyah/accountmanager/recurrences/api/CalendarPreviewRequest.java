package com.malyah.accountmanager.recurrences.api;
import java.time.LocalDate;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import jakarta.validation.constraints.NotNull;
record CalendarPreviewRequest(@NotNull LocalDate firstDueDate, LocalDate lastDueDate,
        @NotNull RecurrenceFrequency frequency) { }
