package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * H08.2 summary as the members read it, generated ({@code id} set) or simulated ({@code id} null). Amounts are
 * decimal strings; {@code items} is the full list, {@code details} the first five of the message.
 */
public record ReminderSummaryView(UUID id, LocalDate date, String slot, String scheduledTime, String timeZone,
        Instant generatedAt, int count, String total, int estimatedCount, String estimatedTotal, int overdueCount,
        int detailCount, int remaining, List<Item> items, String link, String text, List<ChannelView> channels) {

    public record Item(int position, UUID expenseId, UUID recurrenceId, String description, String label,
            String amount, LocalDate dueDate, boolean estimated, boolean overdue, boolean forecast, String origin,
            Integer installmentNumber, Integer installmentCount) { }

    public record ChannelView(String channel, String status, String reason) { }

    /** Preview answer: {@code summary} is null when the slot would have no bill, so nothing would be sent. */
    public record Preview(LocalDate date, String slot, String scheduledTime, String timeZone, LocalDate today,
            ReminderSummaryView summary) { }
}
