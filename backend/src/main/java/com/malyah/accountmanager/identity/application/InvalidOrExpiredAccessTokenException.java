package com.malyah.accountmanager.identity.application;

public final class InvalidOrExpiredAccessTokenException extends RuntimeException {

    public InvalidOrExpiredAccessTokenException() {
        super("O link é inválido, expirou ou já foi utilizado.");
    }
}
