package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PaymentRecord;
import com.malyah.accountmanager.reporting.domain.PaymentIndicators;

/**
 * One active payment. {@code payer} is who paid, {@code recordedBy} who registered the payment (both may differ),
 * and {@code lastCorrection} who changed the charge, value, date or payer afterwards.
 */
public record PaymentRowView(UUID expenseId, String description, String origin, InstallmentLink installment,
        LocalDate dueDate, String chargeAmount, boolean chargeConfirmed, String paidAmount, String adjustment,
        LocalDate paymentDate, UUID payerUserId, String payerDisplayName, UUID recordedByUserId,
        String recordedByDisplayName, Instant recordedAt, boolean batchPayment, String categoryName,
        String responsibleDisplayName, int correctionCount, PaymentRecord.PaymentCorrection lastCorrection) {
    static PaymentRowView of(PaymentRecord record) {
        return new PaymentRowView(record.expenseId(), record.description(), record.origin(), record.installment(),
                record.dueDate(), record.chargeAmount().toPlainString(), record.chargeConfirmed(),
                record.paidAmount().toPlainString(),
                PaymentIndicators.adjustment(record.chargeAmount(), record.paidAmount()).toPlainString(),
                record.paymentDate(), record.payerUserId(), record.payerDisplayName(), record.recordedByUserId(),
                record.recordedByDisplayName(), record.recordedAt(), record.batchPayment(), record.categoryName(),
                record.responsibleDisplayName(), record.correctionCount(), record.lastCorrection());
    }
}
