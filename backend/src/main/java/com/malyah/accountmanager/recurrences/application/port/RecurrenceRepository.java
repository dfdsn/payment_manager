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
}
