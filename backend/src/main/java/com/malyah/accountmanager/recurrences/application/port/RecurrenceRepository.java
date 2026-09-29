package com.malyah.accountmanager.recurrences.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.application.StoredRecurrence;
import com.malyah.accountmanager.recurrences.application.StoredRecurrenceCreation;
import com.malyah.accountmanager.recurrences.domain.RecurrenceDefinition;

public interface RecurrenceRepository {
    StoredRecurrenceCreation createIdempotently(RecurrenceDefinition definition, UUID actorId,
            UUID key, String requestHash, Instant at);
    List<StoredRecurrence> findAll(UUID spaceId);
    StoredRecurrence findById(UUID spaceId, UUID recurrenceId);
    List<com.malyah.accountmanager.recurrences.application.StoredOccurrence> findOccurrences(UUID spaceId,
            java.time.LocalDate from, java.time.LocalDate to);
    com.malyah.accountmanager.recurrences.application.AnticipationClaim claimAnticipation(UUID spaceId,
            UUID actorId, UUID recurrenceId, java.time.LocalDate scheduledDueDate, UUID key,
            String requestHash, Instant at);
    void completeAnticipation(UUID spaceId, UUID actorId, UUID key, UUID expenseId, Instant at);
    /** Confirmed charges of every materialized occurrence of the space, the reference of later estimates. */
    List<com.malyah.accountmanager.recurrences.application.StoredOccurrence> findConfirmedCharges(UUID spaceId);
    /** Serializes a forecast confirmation with generation and other confirmations of the same recurrence. */
    void lockForChargeConfirmation(UUID spaceId, UUID recurrenceId);

    /**
     * H04.5: the definition with its segments and closure. {@code SHARE} serializes materializations with changes,
     * {@code UPDATE} serializes changes, closures and confirmations. Throws RecurrenceNotFoundException.
     */
    com.malyah.accountmanager.recurrences.application.StoredSchedule loadSchedule(UUID spaceId, UUID recurrenceId,
            com.malyah.accountmanager.recurrences.application.ScheduleLock lock);
    List<com.malyah.accountmanager.recurrences.application.StoredSchedule> findSchedules(UUID spaceId);
    com.malyah.accountmanager.recurrences.application.ChangeClaim claimChange(UUID spaceId, UUID actorId, UUID key,
            String requestHash, UUID recurrenceId, Instant at);
    void completeChange(UUID spaceId, UUID actorId, UUID key, UUID changeId, Instant at);
    /** Persists the change event, the segments, the new header and the review flags, in the caller's transaction. */
    void saveChange(com.malyah.accountmanager.recurrences.application.RecurrenceChangeRecord change);
    com.malyah.accountmanager.recurrences.application.RecurrenceChangeView findChange(UUID spaceId, UUID changeId);
    /** Applied changes and closures of the space, oldest first, grouped by recurrence. */
    java.util.Map<UUID, List<com.malyah.accountmanager.recurrences.application.RecurrenceChangeView>> findChanges(UUID spaceId);

    /** H06.3: categories and members a generation could still record in the space. */
    com.malyah.accountmanager.recurrences.application.GenerationEligibility generationEligibility(UUID spaceId);
}
