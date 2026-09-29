package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Sums of one group of planned values of a month. A materialized group comes from active expenses (pending or
 * paid); a forecast group comes from recurrence occurrences not generated yet, which are pending by definition and
 * never paid. {@code estimated} marks values still to be confirmed.
 */
public record PlanningLine(YearMonth month, PlanningOrigin origin, boolean forecast, Situation situation,
        boolean estimated, long count, BigDecimal chargeTotal, BigDecimal paidTotal) {
    public PlanningLine {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(chargeTotal, "chargeTotal");
        Objects.requireNonNull(paidTotal, "paidTotal");
        if (count < 0) throw new IllegalArgumentException("A quantidade não pode ser negativa.");
        if (chargeTotal.signum() < 0 || paidTotal.signum() < 0)
            throw new IllegalArgumentException("Valores do planejamento não podem ser negativos.");
        if (forecast && (origin != PlanningOrigin.RECURRENCE || situation != Situation.PENDING))
            throw new IllegalArgumentException("Uma previsão vem de recorrência e ainda não foi paga.");
        if (situation == Situation.PENDING && paidTotal.signum() != 0)
            throw new IllegalArgumentException("Um valor pendente não possui pagamento.");
    }

    public static PlanningLine forecast(YearMonth month, boolean estimated, BigDecimal amount) {
        return new PlanningLine(month, PlanningOrigin.RECURRENCE, true, Situation.PENDING, estimated, 1, amount,
                BigDecimal.ZERO);
    }
}
