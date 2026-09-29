package com.malyah.accountmanager.notifications.domain;

import java.util.Optional;

/**
 * H08.4 (RF-ALT-15/16): the state of one logical WhatsApp delivery. The attempt ({@link #ATTEMPTING}), the provider
 * acceptance ({@link #ACCEPTED}) and the confirmations reported by the webhook ({@link #SENT}, {@link #DELIVERED},
 * {@link #READ}) are distinct: acceptance is never a delivery. {@link #UNCERTAIN} means there is no proof either
 * way and nothing is resent automatically. Confirmations only move forward, so a repeated or late event never
 * takes a delivery back. H08.5: {@link #RETRY_WAITING} is a delivery whose last attempt certainly did not reach the
 * provider and that waits for the next attempt of the same logical summary inside its window.
 */
public enum WhatsAppDeliveryStatus {
    ATTEMPTING(0, false), RETRY_WAITING(0, false), UNCERTAIN(0, false), ACCEPTED(1, false), SENT(2, false), DELIVERED(3, false),
    READ(4, true), FAILED(5, true), REJECTED(5, true), SKIPPED(5, true);

    private final int rank;
    private final boolean terminal;

    WhatsAppDeliveryStatus(int rank, boolean terminal) {
        this.rank = rank;
        this.terminal = terminal;
    }

    /**
     * Whether a provider confirmation may replace this state: only forward, never out of a final state, and a
     * reported failure never overrides a confirmed delivery or read.
     */
    public boolean canAdvanceTo(WhatsAppDeliveryStatus next) {
        if (terminal || this == ATTEMPTING || this == RETRY_WAITING) return false;
        if (next == FAILED) return rank < DELIVERED.rank;
        return next.webhook() && next.rank > rank;
    }

    /** The states a webhook status can report. */
    public boolean webhook() {
        return this == SENT || this == DELIVERED || this == READ || this == FAILED;
    }

    /** The Meta status names ({@code sent}, {@code delivered}, {@code read}, {@code failed}); others are ignored. */
    public static Optional<WhatsAppDeliveryStatus> fromWebhook(String status) {
        if (status == null) return Optional.empty();
        return switch (status) {
            case "sent" -> Optional.of(SENT);
            case "delivered" -> Optional.of(DELIVERED);
            case "read" -> Optional.of(READ);
            case "failed" -> Optional.of(FAILED);
            default -> Optional.empty();
        };
    }
}
