package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.List;

/** Calculation shown before confirming; nothing is written. */
public record InstallmentPreviewView(String description, String totalAmount, int installmentCount,
        LocalDate firstDueDate, LocalDate lastDueDate, String regularAmount, String lastAmount,
        String lastInstallmentAdjustment, String installmentsSum, List<InstallmentView> installments) {
    public InstallmentPreviewView {
        installments = List.copyOf(installments);
    }
}
