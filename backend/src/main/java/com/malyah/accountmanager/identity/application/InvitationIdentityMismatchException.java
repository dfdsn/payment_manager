package com.malyah.accountmanager.identity.application;

public final class InvitationIdentityMismatchException extends RuntimeException {

    public InvitationIdentityMismatchException() {
        super("Entre com o mesmo email que recebeu o convite.");
    }
}
