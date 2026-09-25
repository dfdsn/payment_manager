package com.malyah.accountmanager.identity.application;

public final class InvitationAdministratorRequiredException extends RuntimeException {

    public InvitationAdministratorRequiredException() {
        super("Somente o administrador do espaço pode gerenciar convites.");
    }
}
