package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;
import com.malyah.accountmanager.notifications.domain.WhatsAppDeliveryStatus;

/**
 * H08.4: one logical WhatsApp delivery (a summary or an administrator test) with its attempts. Only the last four
 * digits of the number are kept here; the provider message id is never shown.
 */
public record StoredDelivery(UUID id, UUID spaceId, WhatsAppSender.Kind kind, UUID summaryId,
        WhatsAppDeliveryStatus status, String skipReason, String failureCode, String providerErrorCode,
        String recipientLastDigits, Integer itemCount, Instant createdAt, Instant attemptedAt, Instant acceptedAt,
        Instant sentAt, Instant deliveredAt, Instant readAt, Instant failedAt, List<Attempt> attempts) {

    public StoredDelivery {
        attempts = List.copyOf(attempts);
    }

    public record Attempt(int number, Instant startedAt, Instant finishedAt, String outcome) { }
}
