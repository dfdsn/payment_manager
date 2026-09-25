package com.malyah.accountmanager.expenses.domain;

public final class CorrectionEligibility {
    private CorrectionEligibility() { }

    public static boolean eligible(
            ExpenseStatus actualStatus, ExpenseStatus expectedStatus, long actualVersion, long expectedVersion) {
        return (actualStatus == ExpenseStatus.PENDING || actualStatus == ExpenseStatus.PAID)
                && actualStatus == expectedStatus
                && expectedVersion >= 0
                && actualVersion == expectedVersion;
    }
}
