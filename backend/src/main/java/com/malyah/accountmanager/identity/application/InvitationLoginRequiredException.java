package com.malyah.accountmanager.identity.application;

public final class InvitationLoginRequiredException extends RuntimeException {

    public InvitationLoginRequiredException() {
        super("Esta conta já existe. Entre com o email convidado para aceitar.");
    }
}
