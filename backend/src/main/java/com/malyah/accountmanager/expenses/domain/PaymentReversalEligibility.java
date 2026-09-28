package com.malyah.accountmanager.expenses.domain;

public final class PaymentReversalEligibility {
    private PaymentReversalEligibility() { }

    public static boolean eligible(ExpenseStatus status, long actualVersion, long expectedVersion) {
        return status == ExpenseStatus.PAID && expectedVersion >= 0 && actualVersion == expectedVersion;
    }
}
