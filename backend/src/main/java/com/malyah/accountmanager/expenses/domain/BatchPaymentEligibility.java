package com.malyah.accountmanager.expenses.domain;

public enum BatchPaymentEligibility {
    ELIGIBLE,
    AMOUNT_UNCONFIRMED,
    STATE_INCOMPATIBLE,
    VERSION_CONFLICT;

    public static BatchPaymentEligibility evaluate(
            boolean chargeConfirmed, ExpenseStatus status, long actualVersion, long expectedVersion) {
        if (!chargeConfirmed) return AMOUNT_UNCONFIRMED;
        if (status != ExpenseStatus.PENDING) return STATE_INCOMPATIBLE;
        return PaymentEligibility.eligible(status, actualVersion, expectedVersion)
                ? ELIGIBLE : VERSION_CONFLICT;
    }
}
