package com.malyah.accountmanager.notifications.application;

/**
 * H08.4: the Meta webhook. {@link #verify} answers the subscription handshake; {@link #receive} checks the
 * signature over the raw body before reading anything and applies the delivery statuses of known messages.
 */
public interface WhatsAppWebhookUseCase {
    Reply verify(String mode, String verifyToken, String challenge);

    Reply receive(byte[] body, String signatureHeader);

    /** HTTP status and plain-text body for the provider; never carries internal detail. */
    record Reply(int status, String body) { }
}
