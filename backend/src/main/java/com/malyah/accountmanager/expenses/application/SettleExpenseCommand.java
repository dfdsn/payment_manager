package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Individual settlement. {@code confirmedChargeAmount} is required only while the charge is still an
 * estimate (RF-DES-04): the confirmation and the payment are then recorded atomically.
 */
public record SettleExpenseCommand(UUID expenseId, long version, String paidAmount,
        LocalDate paymentDate, UUID paidByUserId, String paymentNotes, UUID idempotencyKey,
        String confirmedChargeAmount) {
    public SettleExpenseCommand(UUID expenseId, long version, String paidAmount, LocalDate paymentDate,
            UUID paidByUserId, String paymentNotes, UUID idempotencyKey) {
        this(expenseId, version, paidAmount, paymentDate, paidByUserId, paymentNotes, idempotencyKey, null);
    }
}
