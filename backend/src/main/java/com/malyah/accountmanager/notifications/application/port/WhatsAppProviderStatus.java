package com.malyah.accountmanager.notifications.application.port;

/**
 * H08.1: whether the WhatsApp provider can actually send. Kept apart from the channel the administrator configured.
 * H08.4: the Meta adapter is available only when enabled and fully configured (credentials, template, webhook).
 */
public interface WhatsAppProviderStatus {
    Availability availability();

    /** H08.4: whether a test template (without financial data) is configured for the administrator's test. */
    default boolean testTemplateConfigured() {
        return false;
    }

    record Availability(boolean available, String code, String message) { }
}
