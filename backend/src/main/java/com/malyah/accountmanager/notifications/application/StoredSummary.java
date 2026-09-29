package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.malyah.accountmanager.notifications.domain.ReminderSummary;

/**
 * A generated logical summary: identity space + local date + slot, the content read when the slot was processed,
 * and one record per channel (identity space + date + slot + channel) for the deliveries of H08.3/H08.4.
 */
public record StoredSummary(UUID id, UUID spaceId, LocalTime scheduledTime, String timeZone, Instant scheduledAt,
        Instant generatedAt, ReminderSummary summary, List<Channel> channels) {

    public StoredSummary {
        channels = List.copyOf(channels);
    }

    public enum ChannelType { IN_APP, WHATSAPP }

    public enum ChannelStatus { PLANNED, SKIPPED }

    /** {@code recipientUserId} is set only for a planned WhatsApp delivery: the administrator who consented. */
    public record Channel(ChannelType channel, ChannelStatus status, String skipReason, UUID recipientUserId) { }
}
