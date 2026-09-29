package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.List;

/**
 * H08.4: what the administrator sees about the WhatsApp side of a summary or a test. {@code state} is a delivery
 * state, or {@code NOT_PLANNED} (the channel was not ready when the summary was generated) or {@code WAITING}
 * (planned, not attempted yet). Acceptance by Meta and confirmed delivery are separate states and messages. The
 * number is always masked and no provider id, token or provider text is included.
 */
public record WhatsAppDeliveryView(String state, String stateMessage, String kind, String reason,
        String reasonMessage, String recipientMasked, Integer itemCount, Instant createdAt, Instant attemptedAt,
        Instant acceptedAt, Instant sentAt, Instant deliveredAt, Instant readAt, Instant failedAt,
        List<Attempt> attempts) {

    public WhatsAppDeliveryView {
        attempts = List.copyOf(attempts);
    }

    public record Attempt(int number, Instant startedAt, Instant finishedAt, String outcome) { }
}
