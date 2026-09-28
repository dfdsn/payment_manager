package com.malyah.accountmanager.expenses.application;

public final class ChargeConfirmationRequiredException extends RuntimeException {
    public ChargeConfirmationRequiredException() {
        super("Esta cobrança ainda é estimada. Confirme o valor da cobrança antes de quitá-la.");
    }
}
