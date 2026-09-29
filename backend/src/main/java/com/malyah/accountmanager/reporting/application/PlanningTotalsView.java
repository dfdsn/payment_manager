package com.malyah.accountmanager.reporting.application;

import com.malyah.accountmanager.reporting.domain.PlanningTotals;

/** API view of {@link PlanningTotals}; money as canonical decimal strings with two places. */
public record PlanningTotalsView(long count, String plannedTotal, String confirmedTotal, String estimatedTotal,
        long materializedCount, String materializedTotal, long forecastCount, String forecastTotal, long paidCount,
        String paidTotal, long openCount, String openTotal, String oneOffTotal, String installmentTotal,
        String recurrenceTotal) {
    static PlanningTotalsView of(PlanningTotals totals) {
        return new PlanningTotalsView(totals.count(), totals.plannedTotal().toPlainString(),
                totals.confirmedTotal().toPlainString(), totals.estimatedTotal().toPlainString(),
                totals.materializedCount(), totals.materializedTotal().toPlainString(), totals.forecastCount(),
                totals.forecastTotal().toPlainString(), totals.paidCount(), totals.paidTotal().toPlainString(),
                totals.openCount(), totals.openTotal().toPlainString(), totals.oneOffTotal().toPlainString(),
                totals.installmentTotal().toPlainString(), totals.recurrenceTotal().toPlainString());
    }
}
