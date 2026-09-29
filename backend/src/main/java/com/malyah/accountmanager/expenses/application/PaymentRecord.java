package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * H06.2: the active payment of a paid expense, as it stands now. Reversed payments are not active and therefore
 * never appear; corrections are already reflected in the values and identified by {@code lastCorrection}, while the
 * complete trail stays in the expense history.
 */
public record PaymentRecord(UUID expenseId, String description, String origin, InstallmentLink installment,
        LocalDate dueDate, BigDecimal chargeAmount, boolean chargeConfirmed, BigDecimal paidAmount,
        LocalDate paymentDate, UUID payerUserId, String payerDisplayName, UUID recordedByUserId,
        String recordedByDisplayName, Instant recordedAt, boolean batchPayment, String categoryName,
        String responsibleDisplayName, int correctionCount, PaymentCorrection lastCorrection) {

    /** Fields of a correction that change what the payment view shows. */
    public static final List<String> PAYMENT_FIELDS = List.of("amount", "paidAmount", "paymentDate", "paidByUserId");

    /**
     * Latest correction made after the active payment was recorded that changed the charge, the paid value, the
     * payment date or the payer; {@code changedFields} keeps only those fields.
     */
    public record PaymentCorrection(UUID actorUserId, String actorDisplayName, Instant correctedAt,
            List<String> changedFields) {
        public PaymentCorrection {
            changedFields = List.copyOf(changedFields);
        }
    }
}
