package com.malyah.accountmanager.recurrences.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceDefinition(UUID id, UUID spaceId, String description, BigDecimal amount,
        RecurrenceValueType valueType, RecurrenceFrequency frequency, LocalDate firstDueDate,
        LocalDate lastDueDate, UUID categoryId, UUID responsibleUserId, UUID createdByUserId,
        Instant createdAt, long version) {
    public RecurrenceDefinition {
        description = description == null ? null : description.trim();
        if (description == null || description.isBlank() || description.length() > 200)
            throw new RecurrenceValidationException("description", "Informe uma descrição de até 200 caracteres.");
        if (amount == null || amount.scale() > 2 || amount.compareTo(new BigDecimal("0.01")) < 0
                || amount.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new RecurrenceValidationException("amount", "Informe um valor entre 0,01 e 99.999.999,99 com até duas casas decimais.");
        if (valueType == null) throw new RecurrenceValidationException("valueType", "Informe se o valor é fixo ou estimado.");
        if (frequency == null) throw new RecurrenceValidationException("frequency", "Informe uma frequência válida.");
        if (firstDueDate == null) throw new RecurrenceValidationException("firstDueDate", "Informe o primeiro vencimento.");
        if (lastDueDate != null && lastDueDate.isBefore(firstDueDate))
            throw new RecurrenceValidationException("lastDueDate", "O término não pode ser anterior ao primeiro vencimento.");
    }
}
