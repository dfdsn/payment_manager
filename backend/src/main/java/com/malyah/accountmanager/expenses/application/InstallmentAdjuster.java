package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * H05.3: public contract through which the installments module changes or cancels pending installments of a
 * purchase, so the expense tables stay owned by the expenses module. Callers run in a transaction.
 */
public interface InstallmentAdjuster {
    /** Locks every installment of the purchase for update and returns them in number order. */
    List<InstallmentExpenseSnapshot> lock(UUID spaceId, UUID purchaseId);

    /** Applies all adjustments or none; an installment that changed or is no longer pending fails everything. */
    void apply(UUID spaceId, UUID actorId, UUID changeId, Instant at, List<InstallmentAdjustment> adjustments);
}
