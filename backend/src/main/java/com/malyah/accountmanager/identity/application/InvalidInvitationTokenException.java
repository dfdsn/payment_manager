package com.malyah.accountmanager.identity.application;

public final class InvalidInvitationTokenException extends RuntimeException {

    public InvalidInvitationTokenException() {
        super("O convite é inválido, expirou, já foi utilizado ou foi substituído.");
    }
}
