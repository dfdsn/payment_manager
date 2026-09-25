package com.malyah.accountmanager.identity.application;

public final class InvitationEmailDeliveryException extends RuntimeException {

    public InvitationEmailDeliveryException() {
        super("O convite foi salvo, mas o email não foi entregue. Use reenviar para tentar novamente.");
    }
}
