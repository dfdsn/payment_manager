package com.malyah.accountmanager.identity.application;

public final class ManagedMemberNotFoundException extends RuntimeException {
    public ManagedMemberNotFoundException() {
        super("O membro ativo não foi encontrado neste espaço.");
    }
}
