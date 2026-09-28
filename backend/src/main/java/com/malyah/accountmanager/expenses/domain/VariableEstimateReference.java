package com.malyah.accountmanager.expenses.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;

/**
 * RF-REC-10/13: an estimate uses the latest confirmed charge scheduled before it in the recurrence
 * sequence, or the initial estimate when none exists. Edit time never matters.
 */
public final class VariableEstimateReference {
    private VariableEstimateReference() { }

    public static BigDecimal estimateFor(BigDecimal initialEstimate, Collection<ConfirmedCharge> confirmed,
            LocalDate scheduledDueDate) {
        Objects.requireNonNull(initialEstimate);
        Objects.requireNonNull(scheduledDueDate);
        ConfirmedCharge reference = null;
        for (var charge : confirmed) {
            if (charge.scheduledDueDate().isBefore(scheduledDueDate)
                    && (reference == null || charge.scheduledDueDate().isAfter(reference.scheduledDueDate())))
                reference = charge;
        }
        return reference == null ? initialEstimate : reference.amount();
    }

    public record ConfirmedCharge(LocalDate scheduledDueDate, BigDecimal amount) {
        public ConfirmedCharge {
            Objects.requireNonNull(scheduledDueDate);
            Objects.requireNonNull(amount);
        }
    }
}
