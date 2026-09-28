package com.malyah.accountmanager.installments.application;

public final class InstallmentImpactChangedException extends RuntimeException {
    public InstallmentImpactChangedException() {
        super("As parcelas mudaram desde a revisão. Revise o impacto novamente antes de confirmar.");
    }
}
