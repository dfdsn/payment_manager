package com.malyah.accountmanager.reporting.application;

/** RF-FEC-02: closing with pending entries requires the member to confirm the warning. */
public final class ClosingPendingConfirmationRequiredException extends RuntimeException {
    private final long pendingCount;

    public ClosingPendingConfirmationRequiredException(long pendingCount) {
        super("O mês tem " + pendingCount + " conta(s) pendente(s). Confirme o aviso para fechar mesmo assim.");
        this.pendingCount = pendingCount;
    }

    public long pendingCount() {
        return pendingCount;
    }
}
