package com.malyah.accountmanager.expenses.domain;

/** Decides whether an estimated charge may be replaced by the confirmed billing amount. */
public enum ChargeConfirmationEligibility {
    ELIGIBLE,
    ALREADY_CONFIRMED,
    STATE_INCOMPATIBLE,
    VERSION_CONFLICT;

    public static ChargeConfirmationEligibility evaluate(
            boolean chargeConfirmed, ExpenseStatus status, long actualVersion, long expectedVersion) {
        if (chargeConfirmed) return ALREADY_CONFIRMED;
        if (status != ExpenseStatus.PENDING) return STATE_INCOMPATIBLE;
        return expectedVersion >= 0 && actualVersion == expectedVersion ? ELIGIBLE : VERSION_CONFLICT;
    }

    /** An estimate can only change through confirmation; generic corrections must keep it. */
    public static boolean correctionKeepsEstimate(boolean chargeConfirmed, java.math.BigDecimal current,
            java.math.BigDecimal requested) {
        return chargeConfirmed || current.compareTo(requested) == 0;
    }

}
