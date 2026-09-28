package com.malyah.accountmanager.expenses.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BatchPaymentInstruction(LocalDate date, UUID payerId) {
    public BatchPaymentInstruction {
        if (date == null)
            throw new ExpenseValidationException("paymentDate", "Informe a data do pagamento.");
        if (payerId == null)
            throw new ExpenseValidationException("paidByUserId", "Informe quem pagou.");
    }

    public PaymentDetails paymentFor(BigDecimal chargeAmount) {
        return new PaymentDetails(new ExpenseAmount(chargeAmount), date, payerId, null);
    }
}
