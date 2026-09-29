package com.malyah.accountmanager.notifications.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.notifications.application.SettingsClaim;
import com.malyah.accountmanager.notifications.application.SettingsEvent;
import com.malyah.accountmanager.notifications.application.StoredConsent;
import com.malyah.accountmanager.notifications.application.StoredReminderSettings;
import com.malyah.accountmanager.notifications.domain.ConsentRevocationReason;

/** Persistence of the reminder settings of a space; every write runs inside the caller's transaction. */
public interface ReminderSettingsRepository {
    Optional<StoredReminderSettings> find(UUID spaceId);

    /** Creates the default row if missing and locks it for the rest of the transaction. */
    StoredReminderSettings lock(UUID spaceId, Instant at);

    /** Writes the new state only when the stored version is still {@code settings.version() - 1}. */
    boolean update(StoredReminderSettings settings, UUID actorId);

    Optional<StoredConsent> activeConsent(UUID spaceId);

    void insertConsent(StoredConsent consent, UUID spaceId);

    void revokeConsent(UUID consentId, UUID actorId, ConsentRevocationReason reason, Instant at);

    void appendEvent(SettingsEvent event);

    List<SettingsEvent> events(UUID spaceId, int limit);

    SettingsClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash, Instant at);

    void complete(UUID spaceId, UUID actorId, String operation, UUID key, long resultVersion, Instant at);
}
