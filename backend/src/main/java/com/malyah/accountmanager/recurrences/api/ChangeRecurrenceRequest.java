package com.malyah.accountmanager.recurrences.api;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

/** The full configuration wanted from the period of {@code effectiveDueDate} on; the preview ignores the token. */
record ChangeRecurrenceRequest(@NotNull(message="Informe a versão da recorrência.") Long version,
        @NotNull(message="Informe o vencimento a partir do qual a alteração vale.") LocalDate effectiveDueDate,
        String description, String amount, RecurrenceFrequency frequency, Integer dueDay, UUID categoryId,
        UUID responsibleUserId, String impactToken) { }
