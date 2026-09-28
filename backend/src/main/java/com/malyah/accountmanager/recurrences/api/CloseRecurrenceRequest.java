package com.malyah.accountmanager.recurrences.api;

import java.time.LocalDate;
import jakarta.validation.constraints.NotNull;

/** Ends the recurrence at the period of {@code lastDueDate}. The reason is required to apply. */
record CloseRecurrenceRequest(@NotNull(message="Informe a versão da recorrência.") Long version,
        @NotNull(message="Informe o último vencimento.") LocalDate lastDueDate, String reason, String impactToken) { }
