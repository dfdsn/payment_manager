package com.malyah.accountmanager.notifications.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.notifications.application.SpaceReminderSchedule;
import com.malyah.accountmanager.notifications.application.StoredSummary;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;

/** Persistence of the processed slots and logical summaries (H08.2); every call runs in the caller's transaction. */
public interface ReminderSummaryRepository {
    /** Every space with its time zone and current reminder times (defaults when never configured). */
    List<SpaceReminderSchedule> spaces();

    boolean slotProcessed(UUID spaceId, LocalDate date, ReminderSlot slot);

    /**
     * Records the slot as processed with {@code outcome}; false when it was already recorded, so a slot is
     * processed once whatever the number of workers or re-executions.
     */
    boolean claimSlot(UUID spaceId, LocalDate date, ReminderSlot slot, LocalTime scheduledTime, String outcome,
            Instant at);

    /** Marks a claimed slot as having produced {@code summaryId}. */
    void markGenerated(UUID spaceId, LocalDate date, ReminderSlot slot, UUID summaryId);

    /** Writes the summary with all its items (in order) and one record per channel. */
    void insert(StoredSummary summary);

    Optional<StoredSummary> find(UUID spaceId, UUID summaryId);

    /** The active administrator of the space, the only possible WhatsApp recipient (RF-ALT-01). */
    Optional<UUID> activeAdministrator(UUID spaceId);
}
