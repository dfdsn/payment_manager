package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Exact sums of one group of active entries sharing situation, estimate flag and overdue projection. The sums of
 * payment differences are per entry: {@code increaseTotal} adds what payments exceeded their charge and
 * {@code discountTotal} what they fell short of it.
 */
public record TotalsBucket(Situation situation, boolean estimated, boolean overdue, long count,
        BigDecimal chargeTotal, BigDecimal paidTotal, BigDecimal increaseTotal, BigDecimal discountTotal) {
    public TotalsBucket {
        Objects.requireNonNull(situation, "situation");
        if (count < 0) throw new IllegalArgumentException("A quantidade não pode ser negativa.");
        chargeTotal = nonNegative(chargeTotal, "chargeTotal");
        paidTotal = nonNegative(paidTotal, "paidTotal");
        increaseTotal = nonNegative(increaseTotal, "increaseTotal");
        discountTotal = nonNegative(discountTotal, "discountTotal");
        if (situation == Situation.PAID && overdue)
            throw new IllegalArgumentException("Um lançamento pago não está atrasado.");
        if (situation == Situation.PENDING && (paidTotal.signum() != 0 || increaseTotal.signum() != 0
                || discountTotal.signum() != 0))
            throw new IllegalArgumentException("Um lançamento pendente não possui pagamento.");
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) throw new IllegalArgumentException(name + " não pode ser negativo.");
        return value;
    }
}
