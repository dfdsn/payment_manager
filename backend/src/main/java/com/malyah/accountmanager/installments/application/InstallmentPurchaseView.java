package com.malyah.accountmanager.installments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The purchase header (not an expense) and its installment entries, which are the expenses. */
public record InstallmentPurchaseView(UUID id, String description, String totalAmount, int installmentCount,
        LocalDate firstDueDate, LocalDate lastDueDate, UUID categoryId, String categoryName, UUID responsibleUserId,
        String responsibleDisplayName, UUID createdByUserId, String createdByDisplayName, Instant createdAt,
        String installmentsSum, List<InstallmentView> installments) {
    public InstallmentPurchaseView {
        installments = List.copyOf(installments);
    }
}
