package com.malyah.accountmanager.recurrences.api;

import java.time.LocalDate;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateRecurrenceRequest(@NotBlank @Size(max=200) String description, @NotBlank String amount,
        @NotNull RecurrenceValueType valueType, @NotNull RecurrenceFrequency frequency,
        @NotNull LocalDate firstDueDate, LocalDate lastDueDate, UUID categoryId, UUID responsibleUserId) { }
