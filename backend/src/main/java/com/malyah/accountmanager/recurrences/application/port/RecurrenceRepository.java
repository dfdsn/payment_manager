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
}
