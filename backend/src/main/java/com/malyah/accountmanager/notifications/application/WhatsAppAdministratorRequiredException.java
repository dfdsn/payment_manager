package com.malyah.accountmanager.notifications.application;

public final class WhatsAppAdministratorRequiredException extends RuntimeException {
    public WhatsAppAdministratorRequiredException() {
        super("Somente o administrador acompanha e testa o envio pelo WhatsApp.");
    }
}
