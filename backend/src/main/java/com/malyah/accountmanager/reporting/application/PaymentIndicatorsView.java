package com.malyah.accountmanager.reporting.application;

import com.malyah.accountmanager.reporting.domain.PaymentIndicators;

/** API view of {@link PaymentIndicators}; money as canonical decimal strings with two places. */
public record PaymentIndicatorsView(long count, String paidTotal, String chargeTotal, String adjustmentIncrease,
        String adjustmentDiscount, String adjustmentNet) {
    static PaymentIndicatorsView of(PaymentIndicators indicators) {
        return new PaymentIndicatorsView(indicators.count(), indicators.paidTotal().toPlainString(),
                indicators.chargeTotal().toPlainString(), indicators.adjustmentIncrease().toPlainString(),
                indicators.adjustmentDiscount().toPlainString(), indicators.adjustmentNet().toPlainString());
    }
}
