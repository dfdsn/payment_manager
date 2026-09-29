package com.malyah.accountmanager.reporting.application.port;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.reporting.application.ClosingClaim;
import com.malyah.accountmanager.reporting.application.ClosingVersion;
import com.malyah.accountmanager.reporting.application.StoredClosing;

/** Persistence of month closings; every write runs inside the caller's transaction. */
public interface MonthClosingRepository {
    /** Claims the key; a concurrent request with the same key waits and then sees the first result. */
    ClosingClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash, Instant at);

    void complete(UUID spaceId, UUID actorId, String operation, UUID key, UUID versionId, Instant at);

    /**
     * Creates the header of the month with version 1 in force. Returns false, without writing, when the month
     * already has a closing; a concurrent creation waits for the first one and then returns false.
     */
    boolean create(UUID closingId, UUID spaceId, YearMonth month, Instant at);

    Optional<StoredClosing> find(UUID spaceId, YearMonth month);

    /** Stores the version with its categories and lines. */
    void insertVersion(UUID spaceId, ClosingVersion version);

    /** Audit event of the closing, written in the same transaction as the version it describes. */
    void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId, Instant at);

    Optional<ClosingVersion> version(UUID spaceId, UUID closingId, int number);

    Optional<ClosingVersion> versionById(UUID spaceId, UUID versionId);
}
