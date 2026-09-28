package com.malyah.accountmanager.installments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StoredInstallmentPurchase(UUID id, UUID spaceId, String description, BigDecimal totalAmount,
        int installmentCount, LocalDate firstDueDate, UUID categoryId, String categoryName, UUID responsibleUserId,
        String responsibleDisplayName, UUID createdByUserId, String createdByDisplayName, Instant createdAt) { }
