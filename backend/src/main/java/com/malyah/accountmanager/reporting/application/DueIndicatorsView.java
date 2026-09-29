package com.malyah.accountmanager.reporting.application;

import com.malyah.accountmanager.reporting.domain.DueIndicators;

/** API view of {@link DueIndicators}; money as canonical decimal strings with two places. */
public record DueIndicatorsView(long plannedCount, String plannedTotal, String plannedEstimated,
        long paidCount, String paidTotal,
        long pendingCount, String pendingTotal, String pendingEstimated,
        long overdueCount, String overdueTotal, String overdueEstimated,
        String adjustmentIncrease, String adjustmentDiscount, String adjustmentNet) {
    static DueIndicatorsView of(DueIndicators indicators) {
        return new DueIndicatorsView(indicators.plannedCount(), indicators.plannedTotal().toPlainString(),
                indicators.plannedEstimated().toPlainString(), indicators.paidCount(),
                indicators.paidTotal().toPlainString(), indicators.pendingCount(),
                indicators.pendingTotal().toPlainString(), indicators.pendingEstimated().toPlainString(),
                indicators.overdueCount(), indicators.overdueTotal().toPlainString(),
                indicators.overdueEstimated().toPlainString(), indicators.adjustmentIncrease().toPlainString(),
                indicators.adjustmentDiscount().toPlainString(), indicators.adjustmentNet().toPlainString());
    }
}
