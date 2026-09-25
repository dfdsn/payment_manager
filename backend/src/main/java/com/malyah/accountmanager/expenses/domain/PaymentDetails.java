package com.malyah.accountmanager.expenses.domain;

import java.time.LocalDate;
import java.util.UUID;

public record PaymentDetails(ExpenseAmount amount, LocalDate date, UUID payerId, String notes) {
    public String canonical() {
        return amount.canonical() + ":" + date + ":" + payerId + ":" +
                (notes == null ? "-1:" : notes.length() + ":" + notes);
    }
    public PaymentDetails {
        if (amount == null) throw new ExpenseValidationException("paidAmount", "Informe o valor pago.");
        if (date == null) throw new ExpenseValidationException("paymentDate", "Informe a data do pagamento.");
        if (payerId == null) throw new ExpenseValidationException("paidByUserId", "Informe quem pagou.");
        notes = notes == null || notes.isBlank() ? null : notes.trim();
        if (notes != null && notes.length() > 2000)
            throw new ExpenseValidationException("paymentNotes", "A observação deve ter no máximo 2.000 caracteres.");
    }
}
