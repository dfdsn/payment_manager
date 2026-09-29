package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * H06.3 indicators of a set of planned values (a month or the whole horizon), always computed by summing the
 * lines, never by subtracting one total from another:
 * <ul>
 * <li>Planned: charges of active expenses (pending and paid) plus forecasts.</li>
 * <li>Confirmed and estimated: the same values split by confirmation (RF-REL-04, RF-REC-11).</li>
 * <li>Materialized and forecast: real expenses versus projections not generated yet (RF-REC-08).</li>
 * <li>Paid: amounts actually paid by the paid expenses of the set; open: charges still to pay, forecasts
 * included.</li>
 * <li>Composition by origin: one-off, installments and recurrences.</li>
 * </ul>
 */
public record PlanningTotals(long count, BigDecimal plannedTotal, BigDecimal confirmedTotal,
        BigDecimal estimatedTotal, long materializedCount, BigDecimal materializedTotal, long forecastCount,
        BigDecimal forecastTotal, long paidCount, BigDecimal paidTotal, long openCount, BigDecimal openTotal,
        BigDecimal oneOffTotal, BigDecimal installmentTotal, BigDecimal recurrenceTotal) {

    public static PlanningTotals of(Collection<PlanningLine> lines) {
        long count = 0, materializedCount = 0, forecastCount = 0, paidCount = 0, openCount = 0;
        BigDecimal planned = BigDecimal.ZERO, confirmed = BigDecimal.ZERO, estimated = BigDecimal.ZERO;
        BigDecimal materialized = BigDecimal.ZERO, forecast = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        BigDecimal open = BigDecimal.ZERO, oneOff = BigDecimal.ZERO, installment = BigDecimal.ZERO;
        BigDecimal recurrence = BigDecimal.ZERO;
        for (var line : lines) {
            var charge = line.chargeTotal();
            count += line.count();
            planned = planned.add(charge);
            if (line.estimated()) estimated = estimated.add(charge);
            else confirmed = confirmed.add(charge);
            if (line.forecast()) {
                forecastCount += line.count();
                forecast = forecast.add(charge);
            } else {
                materializedCount += line.count();
                materialized = materialized.add(charge);
            }
            if (line.situation() == Situation.PAID) {
                paidCount += line.count();
                paid = paid.add(line.paidTotal());
            } else {
                openCount += line.count();
                open = open.add(charge);
            }
            switch (line.origin()) {
                case ONE_OFF -> oneOff = oneOff.add(charge);
                case INSTALLMENT -> installment = installment.add(charge);
                case RECURRENCE -> recurrence = recurrence.add(charge);
            }
        }
        return new PlanningTotals(count, Money.of(planned), Money.of(confirmed), Money.of(estimated),
                materializedCount, Money.of(materialized), forecastCount, Money.of(forecast), paidCount,
                Money.of(paid), openCount, Money.of(open), Money.of(oneOff), Money.of(installment),
                Money.of(recurrence));
    }
}
