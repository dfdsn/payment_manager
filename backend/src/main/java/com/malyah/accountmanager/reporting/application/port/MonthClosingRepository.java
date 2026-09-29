package com.malyah.accountmanager.reporting.application.port;

import java.time.Instant;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.reporting.application.ClosingClaim;
import com.malyah.accountmanager.reporting.application.ClosingHead;
import com.malyah.accountmanager.reporting.application.ClosingVersion;
import com.malyah.accountmanager.reporting.application.StoredClosing;
import com.malyah.accountmanager.reporting.application.VersionEntry;

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

    /** Closed months of the year with the version in force, in month order. */
    List<ClosingHead> list(UUID spaceId, Year year);

    Optional<StoredClosing> find(UUID spaceId, YearMonth month);

    /** Reads the header of the month and locks it until the end of the transaction. */
    Optional<StoredClosing> lock(UUID spaceId, YearMonth month);

    /** Makes {@code toVersion} the version in force if {@code fromVersion} still is; false otherwise. */
    boolean advance(UUID spaceId, UUID closingId, int fromVersion, int toVersion, Instant at);

    /** Every version of the closing without categories and lines, oldest first. */
    List<VersionEntry> versions(UUID spaceId, UUID closingId);

    /** Stores the version with its categories and lines. */
    void insertVersion(UUID spaceId, ClosingVersion version);

    /** Audit event of the closing, written in the same transaction as the version it describes. */
    void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId, Instant at);

    Optional<ClosingVersion> version(UUID spaceId, UUID closingId, int number);

    Optional<ClosingVersion> versionById(UUID spaceId, UUID versionId);
}
