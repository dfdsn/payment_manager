package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.UUID;

import com.malyah.accountmanager.reporting.domain.ClosingLine;

/** One entry of a closing, money as decimal strings; {@code paidAmount} and {@code adjustment} only when paid. */
public record ClosingLineView(UUID expenseId, String description, String origin, Integer installmentNumber,
        Integer installmentCount, LocalDate referenceDate, boolean dueDateInformed, String status,
        String chargeAmount, boolean estimated, String paidAmount, String adjustment, boolean overdue,
        UUID categoryId, String categoryName) {
    static ClosingLineView of(ClosingLine line) {
        return new ClosingLineView(line.expenseId(), line.description(), line.origin(), line.installmentNumber(),
                line.installmentCount(), line.referenceDate(), line.dueDateInformed(), line.situation().name(),
                line.chargeAmount().toPlainString(), line.estimated(),
                line.paidAmount() == null ? null : line.paidAmount().toPlainString(),
                line.paidAmount() == null ? null : line.adjustment().toPlainString(), line.overdue(),
                line.categoryId(), line.categoryName());
    }
}
