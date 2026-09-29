package com.malyah.accountmanager.notifications.application.port;

/**
 * H08.1: whether the WhatsApp provider can actually send. Kept apart from the channel the administrator configured;
 * the real Meta adapter and its test send belong to H08.4 (P03).
 */
public interface WhatsAppProviderStatus {
    Availability availability();

    record Availability(boolean available, String code, String message) { }
}
