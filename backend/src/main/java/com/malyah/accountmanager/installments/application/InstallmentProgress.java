package com.malyah.accountmanager.installments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;

/**
 * H05.2: progress of a purchase derived only from the situation of its installments. Amounts are the installment
 * charges grouped by situation; nothing here is a bank or card balance. Overdue installments are also pending.
 */
public record InstallmentProgress(int installmentCount, int paidCount, int pendingCount, int overdueCount,
        int cancelledCount, String paidAmount, String pendingAmount, String overdueAmount, String cancelledAmount,
        LocalDate nextDueDate) {

    static InstallmentProgress of(List<InstallmentExpenseSnapshot> installments, LocalDate today) {
        int paid = 0, pending = 0, overdue = 0, cancelled = 0;
        var paidSum = zero(); var pendingSum = zero(); var overdueSum = zero(); var cancelledSum = zero();
        LocalDate next = null;
        for (var installment : installments) {
            switch (installment.status()) {
                case PAID -> { paid++; paidSum = paidSum.add(installment.amount()); }
                case CANCELLED -> { cancelled++; cancelledSum = cancelledSum.add(installment.amount()); }
                case PENDING -> {
                    pending++;
                    pendingSum = pendingSum.add(installment.amount());
                    if (isOverdue(installment, today)) {
                        overdue++;
                        overdueSum = overdueSum.add(installment.amount());
                    }
                    if (next == null || installment.dueDate().isBefore(next)) next = installment.dueDate();
                }
            }
        }
        return new InstallmentProgress(installments.size(), paid, pending, overdue, cancelled, paidSum.toPlainString(),
                pendingSum.toPlainString(), overdueSum.toPlainString(), cancelledSum.toPlainString(), next);
    }

    static boolean isOverdue(InstallmentExpenseSnapshot installment, LocalDate today) {
        return installment.status() == ExpenseStatus.PENDING && installment.dueDate().isBefore(today);
    }

    private static BigDecimal zero() { return BigDecimal.ZERO.setScale(2); }
}
