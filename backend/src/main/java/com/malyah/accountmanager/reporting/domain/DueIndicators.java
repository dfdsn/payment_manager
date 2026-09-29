package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * RF-REL-01/03/04 and PRD 11.1 for active entries selected by due date (reference date for paid entries without a
 * due date).
 * <ul>
 * <li>Planned: charges of pending and paid entries, confirmed and estimated.</li>
 * <li>Paid: amounts actually paid by entries with an active payment.</li>
 * <li>Pending: charges of open entries, estimates included; never planned minus paid.</li>
 * <li>Overdue: subset of pending, never added to it again.</li>
 * <li>Payment adjustments: paid minus charge of paid entries, split into increases and discounts.</li>
 * </ul>
 */
public record DueIndicators(long plannedCount, BigDecimal plannedTotal, BigDecimal plannedEstimated,
        long paidCount, BigDecimal paidTotal,
        long pendingCount, BigDecimal pendingTotal, BigDecimal pendingEstimated,
        long overdueCount, BigDecimal overdueTotal, BigDecimal overdueEstimated,
        BigDecimal adjustmentIncrease, BigDecimal adjustmentDiscount) {

    public static DueIndicators from(List<TotalsBucket> buckets) {
        long plannedCount = 0, paidCount = 0, pendingCount = 0, overdueCount = 0;
        BigDecimal planned = BigDecimal.ZERO, plannedEstimated = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO, pendingEstimated = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO, overdueEstimated = BigDecimal.ZERO;
        BigDecimal increase = BigDecimal.ZERO, discount = BigDecimal.ZERO;
        for (var bucket : buckets) {
            var estimatedCharge = bucket.estimated() ? bucket.chargeTotal() : BigDecimal.ZERO;
            plannedCount += bucket.count();
            planned = planned.add(bucket.chargeTotal());
            plannedEstimated = plannedEstimated.add(estimatedCharge);
            if (bucket.situation() == Situation.PAID) {
                paidCount += bucket.count();
                paid = paid.add(bucket.paidTotal());
                increase = increase.add(bucket.increaseTotal());
                discount = discount.add(bucket.discountTotal());
            } else {
                pendingCount += bucket.count();
                pending = pending.add(bucket.chargeTotal());
                pendingEstimated = pendingEstimated.add(estimatedCharge);
                if (bucket.overdue()) {
                    overdueCount += bucket.count();
                    overdue = overdue.add(bucket.chargeTotal());
                    overdueEstimated = overdueEstimated.add(estimatedCharge);
                }
            }
        }
        return new DueIndicators(plannedCount, Money.of(planned), Money.of(plannedEstimated), paidCount,
                Money.of(paid), pendingCount, Money.of(pending), Money.of(pendingEstimated), overdueCount,
                Money.of(overdue), Money.of(overdueEstimated), Money.of(increase), Money.of(discount));
    }

    /** Net payment difference: increases minus discounts. */
    public BigDecimal adjustmentNet() {
        return adjustmentIncrease.subtract(adjustmentDiscount);
    }
}
