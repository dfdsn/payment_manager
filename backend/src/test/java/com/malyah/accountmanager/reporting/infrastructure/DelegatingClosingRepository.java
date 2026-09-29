package com.malyah.accountmanager.reporting.infrastructure;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.reporting.application.ClosingClaim;
import com.malyah.accountmanager.reporting.application.ClosingVersion;
import com.malyah.accountmanager.reporting.application.StoredClosing;
import com.malyah.accountmanager.reporting.application.port.MonthClosingRepository;

/** Test decorator: forwards every call, so a test can override one step to inject a failure or a pause. */
class DelegatingClosingRepository implements MonthClosingRepository {
    private final MonthClosingRepository delegate;

    DelegatingClosingRepository(MonthClosingRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public ClosingClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash,
            Instant at) {
        return delegate.claim(spaceId, actorId, operation, key, requestHash, at);
    }

    @Override
    public void complete(UUID spaceId, UUID actorId, String operation, UUID key, UUID versionId, Instant at) {
        delegate.complete(spaceId, actorId, operation, key, versionId, at);
    }

    @Override
    public boolean create(UUID closingId, UUID spaceId, YearMonth month, Instant at) {
        return delegate.create(closingId, spaceId, month, at);
    }

    @Override
    public Optional<StoredClosing> find(UUID spaceId, YearMonth month) {
        return delegate.find(spaceId, month);
    }

    @Override
    public void insertVersion(UUID spaceId, ClosingVersion version) {
        delegate.insertVersion(spaceId, version);
    }

    @Override
    public void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId,
            Instant at) {
        delegate.recordEvent(closingId, spaceId, versionNumber, eventType, actorId, at);
    }

    @Override
    public Optional<ClosingVersion> version(UUID spaceId, UUID closingId, int number) {
        return delegate.version(spaceId, closingId, number);
    }

    @Override
    public Optional<ClosingVersion> versionById(UUID spaceId, UUID versionId) {
        return delegate.versionById(spaceId, versionId);
    }
}
