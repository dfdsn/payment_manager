package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InstallmentExpensesCommand(UUID spaceId, UUID purchaseId, UUID createdByUserId, Instant createdAt,
        String description, UUID categoryId, UUID responsibleUserId, List<Entry> entries) {
    public InstallmentExpensesCommand {
        entries = List.copyOf(entries);
    }

    public record Entry(int number, BigDecimal amount, LocalDate dueDate) { }
}
