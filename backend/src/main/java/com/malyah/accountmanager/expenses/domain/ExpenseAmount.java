package com.malyah.accountmanager.expenses.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ExpenseAmount(BigDecimal value) {
    private static final BigDecimal MAXIMUM = new BigDecimal("99999999.99");

    public ExpenseAmount {
        if (value == null) {
            throw new ExpenseValidationException("amount", "Informe o valor da despesa.");
        }
        try {
            value = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ExpenseValidationException("amount", "Use no máximo duas casas decimais.");
        }
        if (value.signum() <= 0 || value.compareTo(MAXIMUM) > 0) {
            throw new ExpenseValidationException(
                    "amount", "O valor deve estar entre R$ 0,01 e R$ 99.999.999,99.");
        }
    }

    public static ExpenseAmount parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new ExpenseValidationException("amount", "Informe o valor da despesa.");
        }
        try {
            return new ExpenseAmount(new BigDecimal(rawValue.trim()));
        } catch (NumberFormatException exception) {
            throw new ExpenseValidationException("amount", "Informe um valor decimal válido.");
        }
    }

    public String canonical() {
        return value.toPlainString();
    }
}
