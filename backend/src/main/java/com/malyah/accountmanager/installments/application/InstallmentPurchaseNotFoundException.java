package com.malyah.accountmanager.installments.application;

/** The purchase does not exist in the active space; other spaces' purchases are indistinguishable from missing ones. */
public final class InstallmentPurchaseNotFoundException extends RuntimeException {
    public InstallmentPurchaseNotFoundException() {
        super("Compra parcelada não encontrada neste espaço.");
    }
}
