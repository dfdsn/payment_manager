package com.malyah.accountmanager.identity.application;

public final class SetupSecretUnavailableException extends RuntimeException {

    public SetupSecretUnavailableException() {
        super("O segredo temporário de configuração não está disponível no servidor.");
    }
}
