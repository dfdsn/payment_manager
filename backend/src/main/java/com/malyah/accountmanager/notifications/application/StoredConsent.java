package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;

/** An active WhatsApp consent: who granted it, for which number, which text and when (UTC). */
public record StoredConsent(UUID id, UUID userId, String grantedByDisplayName, WhatsAppRecipient recipient,
        String textVersion, Instant grantedAt) { }
