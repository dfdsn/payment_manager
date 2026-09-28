package com.malyah.accountmanager.installments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** H05.2: a purchase in the list, with its progress; the installments themselves are in the detail. */
public record InstallmentPurchaseSummary(UUID id, String description, String totalAmount, int installmentCount,
        LocalDate firstDueDate, LocalDate lastDueDate, String categoryName, String responsibleDisplayName,
        Instant createdAt, InstallmentProgress progress) { }
