package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.UUID;

/** Data of a purchase: total already includes any interest of the purchase (no financing is calculated). */
public record InstallmentPurchaseCommand(String description, String totalAmount, Integer installmentCount,
        LocalDate firstDueDate, UUID categoryId, UUID responsibleUserId, UUID idempotencyKey) { }
