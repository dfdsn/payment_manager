package com.malyah.accountmanager.identity.application;

public final class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email ou senha inválidos.");
    }
}
