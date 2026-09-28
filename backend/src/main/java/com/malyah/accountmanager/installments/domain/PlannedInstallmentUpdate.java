package com.malyah.accountmanager.installments.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** New values of one pending installment and the fields that actually differ from its current values. */
public record PlannedInstallmentUpdate(int number, String description, LocalDate dueDate, UUID categoryId,
        UUID responsibleUserId, List<String> changedFields) {
    public PlannedInstallmentUpdate {
        changedFields = List.copyOf(changedFields);
    }
}
