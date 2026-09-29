package com.malyah.accountmanager.notifications.application;

public final class WhatsAppRecipientMismatchException extends RuntimeException {
    public WhatsAppRecipientMismatchException() {
        super("O número confirmado é diferente do número cadastrado. Atualize a tela e confirme de novo.");
    }
}
