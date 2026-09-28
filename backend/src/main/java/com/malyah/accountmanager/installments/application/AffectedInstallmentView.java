package com.malyah.accountmanager.installments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AffectedInstallmentView(int number, UUID expenseId, long version, String amount, LocalDate dueDate,
        List<InstallmentFieldChangeView> changes) {
    public AffectedInstallmentView {
        changes = List.copyOf(changes);
    }
}
