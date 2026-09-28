package com.malyah.accountmanager.expenses.domain;

import java.util.Locale;

public record CategoryName(String value, String normalized) {
    public CategoryName(String value) {
        this(validate(value), normalize(value));
    }

    private static String validate(String value) {
        if (value == null || value.isBlank()) throw new ExpenseValidationException("name", "Informe o nome da categoria.");
        var trimmed = value.trim();
        if (trimmed.length() > 60) throw new ExpenseValidationException("name", "O nome deve ter no máximo 60 caracteres.");
        return trimmed;
    }

    private static String normalize(String value) {
        return validate(value).toLowerCase(Locale.ROOT);
    }
}
