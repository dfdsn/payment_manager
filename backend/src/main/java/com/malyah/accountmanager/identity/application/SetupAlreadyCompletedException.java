package com.malyah.accountmanager.identity.application;

public final class SetupAlreadyCompletedException extends RuntimeException {

    public SetupAlreadyCompletedException() {
        super("A configuração inicial já foi concluída.");
    }
}
