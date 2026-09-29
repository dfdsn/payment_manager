package com.malyah.accountmanager.installments.application;

import java.util.UUID;

public record StoredInstallmentChange(UUID id, UUID purchaseId, String changeType, int affectedCount,
        int preservedCount, UUID replacementPurchaseId) { }
