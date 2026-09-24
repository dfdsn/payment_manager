package com.malyah.accountmanager.identity.application;

public final class InvalidSetupSecretException extends RuntimeException {

    public InvalidSetupSecretException() {
        super("O segredo de configuração inicial é inválido.");
    }
}
