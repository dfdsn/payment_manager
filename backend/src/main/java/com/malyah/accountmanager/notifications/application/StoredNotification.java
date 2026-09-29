package com.malyah.accountmanager.notifications.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * H08.3: one member's notification with the head of the summary it points to, exactly as generated (the summary
 * never changes afterwards). {@code failureCode} is set only for a WhatsApp failure addressed to the administrator.
 */
public record StoredNotification(UUID id, Type type, String failureCode, Instant createdAt, Instant readAt,
        Instant dismissedAt, SummaryHead summary) {

    public enum Type { REMINDER_SUMMARY, WHATSAPP_DELIVERY_FAILURE }

    public record SummaryHead(UUID id, LocalDate date, String slot, LocalTime scheduledTime, String timeZone,
            int count, BigDecimal total, int estimatedCount, BigDecimal estimatedTotal, int overdueCount) { }
}
