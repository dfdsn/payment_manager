package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * H08.3 notification as its recipient reads it. {@code summary} is the historical content of the slot (amounts as
 * decimal strings); the current situation of each bill is on the summary page. {@code failure} only exists for
 * the administrator's WhatsApp failures and holds a catalog message, never a provider response.
 */
public record MemberNotificationView(UUID id, String type, String title, String message, Instant createdAt,
        Instant readAt, Instant dismissedAt, Summary summary, Failure failure) {

    public record Summary(UUID id, LocalDate date, String slot, String scheduledTime, String timeZone, int count,
            String total, int estimatedCount, String estimatedTotal, int overdueCount, int remaining, String link) { }

    public record Failure(String code, String message) { }

    public record Page(List<MemberNotificationView> items, int page, int size, long totalItems, int totalPages,
            long unreadCount, String view) { }

    public record UnreadCount(long unreadCount) { }
}
