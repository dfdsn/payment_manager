package com.malyah.accountmanager.notifications.domain;

/**
 * H08.1: effective state of the administrator's WhatsApp channel. The configured part (recipient, consent,
 * activation) is kept apart from the provider availability, so a configured channel is never reported as able to
 * send while the integration is missing.
 */
public enum WhatsAppChannelState {
    RECIPIENT_REQUIRED, CONSENT_REQUIRED, DISABLED, PROVIDER_UNAVAILABLE, READY;

    public static WhatsAppChannelState of(boolean hasRecipient, boolean consentActive, boolean enabled,
            boolean providerAvailable) {
        if (!hasRecipient) return RECIPIENT_REQUIRED;
        if (!consentActive) return CONSENT_REQUIRED;
        if (!enabled) return DISABLED;
        return providerAvailable ? READY : PROVIDER_UNAVAILABLE;
    }
}
