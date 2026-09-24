package com.malyah.accountmanager.identity.application;

public final class AuthenticatedUserContextNotFoundException extends RuntimeException {

    public AuthenticatedUserContextNotFoundException() {
        super("O usuário autenticado não possui acesso ativo a um espaço.");
    }
}
