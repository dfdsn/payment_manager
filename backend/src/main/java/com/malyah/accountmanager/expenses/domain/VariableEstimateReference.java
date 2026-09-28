package com.malyah.accountmanager.expenses.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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

    /**
     * H04.5: a change of the estimate "from this period on" restarts the reference. For a given occurrence the
     * base is the latest estimate set at or before its period; only confirmations from that period on count.
     */
    public static BigDecimal estimateFor(Collection<EstimateBase> bases, Collection<ConfirmedCharge> confirmed,
            LocalDate scheduledDueDate) {
        Objects.requireNonNull(scheduledDueDate);
        var month = YearMonth.from(scheduledDueDate);
        EstimateBase base = null;
        for (var candidate : bases)
            if (!candidate.fromMonth().isAfter(month)
                    && (base == null || candidate.fromMonth().isAfter(base.fromMonth()))) base = candidate;
        if (base == null) throw new IllegalArgumentException("No estimate applies to " + scheduledDueDate);
        var start = base.fromMonth();
        var applicable = confirmed.stream()
                .filter(charge -> !YearMonth.from(charge.scheduledDueDate()).isBefore(start)).toList();
        return estimateFor(base.amount(), applicable, scheduledDueDate);
    }

    public record EstimateBase(YearMonth fromMonth, BigDecimal amount) {
        public EstimateBase {
            Objects.requireNonNull(fromMonth);
            Objects.requireNonNull(amount);
        }
    }

    public record ConfirmedCharge(LocalDate scheduledDueDate, BigDecimal amount) {
        public ConfirmedCharge {
            Objects.requireNonNull(scheduledDueDate);
            Objects.requireNonNull(amount);
        }
    }
}
