package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * E07: one active entry as a month closing keeps it. Labels (description, category name) are copies taken when the
 * snapshot was made, so a saved version never depends on the current expense. {@code overdue} is the projection on
 * the business date of the snapshot and is informative only: the passage of time never changes a snapshot.
 */
public record ClosingLine(UUID expenseId, String description, String origin, Integer installmentNumber,
        Integer installmentCount, LocalDate referenceDate, boolean dueDateInformed, Situation situation,
        BigDecimal chargeAmount, boolean estimated, BigDecimal paidAmount, boolean overdue, UUID categoryId,
        String categoryName) {
    public ClosingLine {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(referenceDate, "referenceDate");
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(chargeAmount, "chargeAmount");
        if (chargeAmount.signum() <= 0) throw new IllegalArgumentException("A cobrança deve ser positiva.");
        if ((situation == Situation.PAID) != (paidAmount != null))
            throw new IllegalArgumentException("Somente lançamento pago tem valor pago.");
        if (paidAmount != null && paidAmount.signum() <= 0)
            throw new IllegalArgumentException("O valor pago deve ser positivo.");
        if (situation == Situation.PAID && overdue)
            throw new IllegalArgumentException("Um lançamento pago não está atrasado.");
        if ((installmentNumber == null) != (installmentCount == null))
            throw new IllegalArgumentException("Parcela exige número e quantidade.");
        if ((categoryId == null) != (categoryName == null))
            throw new IllegalArgumentException("Categoria exige identificador e nome.");
    }

    /** Payment difference of a paid entry: positive for an increase, negative for a discount. */
    public BigDecimal adjustment() {
        return paidAmount == null ? BigDecimal.ZERO : paidAmount.subtract(chargeAmount);
    }

    /**
     * The fields that decide the values and classifications of a closing (RF-FEC-05): month membership and date,
     * situation, charge, estimate, paid value and category. Labels and the overdue projection are not part of it.
     */
    String contentKey() {
        return expenseId + "|" + referenceDate + "|" + situation + "|" + chargeAmount.setScale(2) + "|" + estimated
                + "|" + (paidAmount == null ? "-" : paidAmount.setScale(2)) + "|"
                + (categoryId == null ? "-" : categoryId);
    }
}
