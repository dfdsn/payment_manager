package com.malyah.accountmanager.expenses.api;

import java.time.LocalDate;

import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateExpenseRequest(
        @NotBlank(message = "Informe a descrição.")
        @Size(max = 200, message = "A descrição deve ter no máximo 200 caracteres.")
        String description,
        @NotBlank(message = "Informe o valor.") String amount,
        @NotNull(message = "Informe a situação.") ExpenseStatus status,
        LocalDate dueDate,
        LocalDate paymentDate,
        @Size(max = 2000, message = "A observação deve ter no máximo 2.000 caracteres.") String notes,
        String paidAmount, java.util.UUID paidByUserId, String paymentNotes) {
}
