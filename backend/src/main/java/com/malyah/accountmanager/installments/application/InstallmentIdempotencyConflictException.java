package com.malyah.accountmanager.installments.application;

public final class InstallmentIdempotencyConflictException extends RuntimeException {
    public InstallmentIdempotencyConflictException() {
        super("Esta chave de repetição já foi usada com outros dados de compra.");
    }
}
