package com.malyah.accountmanager.installments.application;

import java.util.UUID;

public record InstallmentChangeResult(UUID changeId, String changeType, int affectedCount, int preservedCount,
        InstallmentPurchaseView purchase, InstallmentPurchaseView replacement, boolean replayed) { }
