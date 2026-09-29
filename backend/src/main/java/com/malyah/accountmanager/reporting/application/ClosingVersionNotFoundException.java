package com.malyah.accountmanager.reporting.application;

public final class ClosingVersionNotFoundException extends RuntimeException {
    public ClosingVersionNotFoundException() {
        super("Versão do fechamento não encontrada.");
    }
}
