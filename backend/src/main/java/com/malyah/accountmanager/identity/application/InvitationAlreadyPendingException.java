package com.malyah.accountmanager.identity.application;

public final class InvitationAlreadyPendingException extends RuntimeException {

    public InvitationAlreadyPendingException() {
        super("Já existe um convite pendente. Reenvie-o ou revogue-o antes de convidar outro email.");
    }
}
