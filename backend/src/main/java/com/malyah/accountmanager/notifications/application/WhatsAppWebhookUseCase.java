package com.malyah.accountmanager.notifications.application;

/**
 * T36: the Evolution API webhook. {@link #receive} checks the shared secret header before reading anything and
 * applies the delivery statuses of known messages.
 */
public interface WhatsAppWebhookUseCase {
    Reply receive(byte[] body, String tokenHeader);

    /** HTTP status and plain-text body for the provider; never carries internal detail. */
    record Reply(int status, String body) { }
}
