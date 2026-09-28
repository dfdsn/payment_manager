package com.malyah.accountmanager.recurrences.domain;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The editable configuration of a recurrence for a range of periods (H04.5). */
public record RecurrenceConfiguration(String description, BigDecimal amount, RecurrenceFrequency frequency,
        int baseDay, UUID categoryId, UUID responsibleUserId) {
    private static final BigDecimal MINIMUM = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM = new BigDecimal("99999999.99");

    public RecurrenceConfiguration {
        description = description == null ? null : description.trim();
        if (description == null || description.isBlank() || description.length() > 200)
            throw new RecurrenceValidationException("description", "Informe uma descrição de até 200 caracteres.");
        if (amount == null || amount.scale() > 2 || amount.compareTo(MINIMUM) < 0 || amount.compareTo(MAXIMUM) > 0)
            throw new RecurrenceValidationException("amount", "Informe um valor entre 0,01 e 99.999.999,99 com até duas casas decimais.");
        if (frequency == null) throw new RecurrenceValidationException("frequency", "Informe uma frequência válida.");
        if (baseDay < 1 || baseDay > 31)
            throw new RecurrenceValidationException("dueDay", "Informe um dia de vencimento entre 1 e 31.");
    }

    /** Fields whose value differs from {@code other}. */
    public Set<RecurrenceChangeField> differencesFrom(RecurrenceConfiguration other) {
        var fields = EnumSet.noneOf(RecurrenceChangeField.class);
        if (!description.equals(other.description)) fields.add(RecurrenceChangeField.DESCRIPTION);
        if (amount.compareTo(other.amount) != 0) fields.add(RecurrenceChangeField.AMOUNT);
        if (frequency != other.frequency) fields.add(RecurrenceChangeField.FREQUENCY);
        if (baseDay != other.baseDay) fields.add(RecurrenceChangeField.DUE_DAY);
        if (!Objects.equals(categoryId, other.categoryId)) fields.add(RecurrenceChangeField.CATEGORY);
        if (!Objects.equals(responsibleUserId, other.responsibleUserId)) fields.add(RecurrenceChangeField.RESPONSIBLE);
        return fields;
    }

    /** Copies only the given fields from {@code source}; the others keep this configuration's values. */
    public RecurrenceConfiguration with(RecurrenceConfiguration source, Set<RecurrenceChangeField> fields) {
        return new RecurrenceConfiguration(
                fields.contains(RecurrenceChangeField.DESCRIPTION) ? source.description : description,
                fields.contains(RecurrenceChangeField.AMOUNT) ? source.amount : amount,
                fields.contains(RecurrenceChangeField.FREQUENCY) ? source.frequency : frequency,
                fields.contains(RecurrenceChangeField.DUE_DAY) ? source.baseDay : baseDay,
                fields.contains(RecurrenceChangeField.CATEGORY) ? source.categoryId : categoryId,
                fields.contains(RecurrenceChangeField.RESPONSIBLE) ? source.responsibleUserId : responsibleUserId);
    }
}
