package com.malyah.accountmanager.identity.application;

public final class NoPendingInvitationException extends RuntimeException {

    public NoPendingInvitationException() {
        super("Não existe convite pendente para reenviar.");
    }
}
