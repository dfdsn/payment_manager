package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * RF-REL-02 and PRD 11.1 for active payments selected by their effective payment date. Reversed payments are not
 * active and cancelled entries have no payment, so neither reaches these sums. The adjustment is the difference
 * between what was paid and the charge of each payment, split into increases and discounts; there is no pending,
 * overdue, balance or financial result in this view.
 */
public record PaymentIndicators(long count, BigDecimal paidTotal, BigDecimal chargeTotal,
        BigDecimal adjustmentIncrease, BigDecimal adjustmentDiscount) {

    public static PaymentIndicators from(List<TotalsBucket> buckets) {
        long count = 0;
        BigDecimal paid = BigDecimal.ZERO, charge = BigDecimal.ZERO, increase = BigDecimal.ZERO,
                discount = BigDecimal.ZERO;
        for (var bucket : buckets) {
            if (bucket.situation() != Situation.PAID)
                throw new IllegalArgumentException("A visão de pagamentos só contém quitações ativas.");
            count += bucket.count();
            paid = paid.add(bucket.paidTotal());
            charge = charge.add(bucket.chargeTotal());
            increase = increase.add(bucket.increaseTotal());
            discount = discount.add(bucket.discountTotal());
        }
        return new PaymentIndicators(count, Money.of(paid), Money.of(charge), Money.of(increase), Money.of(discount));
    }

    /** Net payment difference: increases minus discounts. */
    public BigDecimal adjustmentNet() {
        return adjustmentIncrease.subtract(adjustmentDiscount);
    }

    /** Signed adjustment of one payment: positive for an increase, negative for a discount. */
    public static BigDecimal adjustment(BigDecimal charge, BigDecimal paid) {
        return Money.of(paid.subtract(charge));
    }
}
