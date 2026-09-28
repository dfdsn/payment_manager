package com.malyah.accountmanager.installments.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.installments.domain.InstallmentChangeScope;
import jakarta.validation.constraints.NotNull;

record InstallmentChangeRequest(@NotNull(message = "Escolha a parcela inicial.") Integer fromNumber,
        InstallmentChangeScope scope, List<String> changedFields, String description, UUID categoryId,
        UUID responsibleUserId, LocalDate dueDate, String impactToken) { }
