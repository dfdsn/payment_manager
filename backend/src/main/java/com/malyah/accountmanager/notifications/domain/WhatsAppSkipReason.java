package com.malyah.accountmanager.notifications.domain;

/**
 * H08.4 (RF-ALT-13): why a planned WhatsApp summary was not sent when it was revalidated just before the call.
 * {@link #PROVIDER_UNAVAILABLE} and {@link #WINDOW_CLOSED} are failures the administrator is told about; the others
 * follow a decision (disable, revoke, change) or a summary that became empty, and are not failures.
 */
public enum WhatsAppSkipReason {
    ADMINISTRATOR_CHANGED, CONSENT_REVOKED, RECIPIENT_CHANGED, DISABLED, PROVIDER_UNAVAILABLE, WINDOW_CLOSED, EMPTY
}
