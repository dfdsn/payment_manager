package com.malyah.accountmanager.installments.api;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

record InstallmentPurchaseRequest(
        @NotBlank(message = "Informe a descrição da compra.") String description,
        @NotBlank(message = "Informe o valor total da compra.") String totalAmount,
        @NotNull(message = "Informe a quantidade de parcelas.") Integer installmentCount,
        @NotNull(message = "Informe o vencimento da primeira parcela.") LocalDate firstDueDate,
        UUID categoryId, UUID responsibleUserId) { }
