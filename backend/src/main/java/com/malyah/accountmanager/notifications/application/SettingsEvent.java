package com.malyah.accountmanager.notifications.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record of a settings change; {@code detail} never carries the full phone number. H08.5: a
 * {@link Type#CHANNEL_SUSPENDED} is made by the system and has no actor.
 */
public record SettingsEvent(UUID id, UUID spaceId, UUID actorId, String actorDisplayName, Type type,
        long fromVersion, long toVersion, String detail, Instant occurredAt) {

    public enum Type {
        SCHEDULE_CHANGED, RECIPIENT_CHANGED, CONSENT_GRANTED, CONSENT_REVOKED, CHANNEL_ENABLED, CHANNEL_DISABLED,
        CHANNEL_SUSPENDED
    }
}
