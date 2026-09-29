package com.malyah.accountmanager.notifications.application;

public final class WhatsAppConsentNotActiveException extends RuntimeException {
    public WhatsAppConsentNotActiveException() {
        super("Não há consentimento ativo para revogar.");
    }
}
