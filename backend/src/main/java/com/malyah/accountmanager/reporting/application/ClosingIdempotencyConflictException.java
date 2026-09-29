package com.malyah.accountmanager.reporting.application;

public final class ClosingIdempotencyConflictException extends RuntimeException {
    public ClosingIdempotencyConflictException() {
        super("Esta chave de repetição já foi usada para outro pedido.");
    }
}
