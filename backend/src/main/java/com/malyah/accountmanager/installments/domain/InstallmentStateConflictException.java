package com.malyah.accountmanager.installments.domain;

/** The operation targets an installment that is no longer pending; paid and cancelled ones are never rewritten. */
public final class InstallmentStateConflictException extends RuntimeException {
    public InstallmentStateConflictException(String message) {
        super(message);
    }
}
