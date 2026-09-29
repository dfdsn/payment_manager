package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.installments.domain.InstallmentChangeScope;

/** H05.3: change from installment {@code fromNumber}; only the listed fields change (a null value clears it). */
public record InstallmentChangeCommand(UUID purchaseId, int fromNumber, InstallmentChangeScope scope,
        List<String> changedFields, String description, UUID categoryId, UUID responsibleUserId, LocalDate dueDate,
        String impactToken, UUID idempotencyKey) {
    public InstallmentChangeCommand {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
