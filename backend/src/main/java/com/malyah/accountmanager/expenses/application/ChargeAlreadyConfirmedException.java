package com.malyah.accountmanager.expenses.application;

public final class ChargeAlreadyConfirmedException extends RuntimeException {
    public ChargeAlreadyConfirmedException() {
        super("O valor desta cobrança já foi confirmado. Para ajustá-lo, use a correção do lançamento.");
    }
}
