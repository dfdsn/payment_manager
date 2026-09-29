package com.malyah.accountmanager.reporting.application;

/** Another member generated a version after the one this request expected. */
public final class ClosingVersionConflictException extends RuntimeException {
    private final int currentVersion;

    public ClosingVersionConflictException(int currentVersion) {
        super("Outra versão foi gerada enquanto você revisava (versão vigente: " + currentVersion
                + "). Confira o retrato atual antes de gerar de novo.");
        this.currentVersion = currentVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}
