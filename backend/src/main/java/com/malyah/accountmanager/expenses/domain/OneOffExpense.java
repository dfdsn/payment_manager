package com.malyah.accountmanager.expenses.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record OneOffExpense(
        UUID id,
        UUID spaceId,
        String description,
        ExpenseAmount amount,
        ExpenseStatus status,
        LocalDate dueDate,
        LocalDate paymentDate,
        LocalDate referenceDate,
        String notes,
        UUID createdByUserId,
        Instant createdAt,
        PaymentDetails payment) {

    public OneOffExpense(UUID id, UUID spaceId, String description, ExpenseAmount amount,
            ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate, LocalDate referenceDate,
            String notes, UUID createdByUserId, Instant createdAt) {
        this(id, spaceId, description, amount, status, dueDate, paymentDate, referenceDate,
                notes, createdByUserId, createdAt, null);
    }

    public OneOffExpense {
        Objects.requireNonNull(id);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdByUserId);
        Objects.requireNonNull(createdAt);
        description = normalizeDescription(description);
        notes = normalizeNotes(notes);

        if (status == ExpenseStatus.PENDING) {
            if (payment != null) throw new ExpenseValidationException("payment", "Despesa pendente não possui pagamento.");
            if (dueDate == null) {
                throw new ExpenseValidationException("dueDate", "Informe o vencimento da despesa pendente.");
            }
            if (paymentDate != null) {
                throw new ExpenseValidationException("paymentDate", "Despesa pendente não possui data de pagamento.");
            }
            referenceDate = dueDate;
        } else if (status == ExpenseStatus.PAID) {
            if (paymentDate == null) {
                throw new ExpenseValidationException("paymentDate", "Informe a data de pagamento.");
            }
            referenceDate = dueDate == null ? paymentDate : dueDate;
            if (payment == null) payment = new PaymentDetails(amount, paymentDate, createdByUserId, null);
        } else {
            throw new ExpenseValidationException("status", "Cadastre a despesa como pendente ou paga.");
        }
    }

    public boolean overdueOn(LocalDate currentDate) {
        return status == ExpenseStatus.PENDING && dueDate.isBefore(currentDate);
    }

    private static String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            throw new ExpenseValidationException("description", "Informe a descrição da despesa.");
        }
        var normalized = value.trim();
        if (normalized.length() > 200) {
            throw new ExpenseValidationException("description", "A descrição deve ter no máximo 200 caracteres.");
        }
        return normalized;
    }

    private static String normalizeNotes(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > 2000) {
            throw new ExpenseValidationException("notes", "A observação deve ter no máximo 2.000 caracteres.");
        }
        return normalized;
    }
}
