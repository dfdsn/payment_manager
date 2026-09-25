package com.malyah.accountmanager.identity.application;

public final class InvitationTargetUnavailableException extends RuntimeException {

    public InvitationTargetUnavailableException() {
        super("Este email já está associado a um espaço ativo e não pode aceitar o convite.");
    }
}
