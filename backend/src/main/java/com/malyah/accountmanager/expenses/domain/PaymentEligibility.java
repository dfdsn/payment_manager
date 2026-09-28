package com.malyah.accountmanager.expenses.domain;

public final class PaymentEligibility {
    private PaymentEligibility() { }
    public static boolean eligible(ExpenseStatus status, long actualVersion, long expectedVersion) {
        return status == ExpenseStatus.PENDING && expectedVersion >= 0 && actualVersion == expectedVersion;
    }
}
