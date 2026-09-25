package com.malyah.accountmanager.expenses.domain;

public record ExpenseActionReason(String value) {
    public ExpenseActionReason {
        if (value == null || value.isBlank())
            throw new ExpenseValidationException("reason", "Informe o motivo da operação.");
        value = value.trim();
        if (value.length() > 2000)
            throw new ExpenseValidationException("reason", "O motivo deve ter no máximo 2.000 caracteres.");
    }
}
