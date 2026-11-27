package com.malyah.accountmanager.notifications.application.port;

/**
 * H08.1: whether the WhatsApp provider can actually send. Kept apart from the channel the administrator configured.
 * T36: the Evolution API adapter is available only when enabled and fully configured (address, key, instance and
 * webhook secret). Whether the instance is connected to WhatsApp is checked right before each send.
 */
public interface WhatsAppProviderStatus {
    Availability availability();

    record Availability(boolean available, String code, String message) { }
}
