package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

/** Result of claiming an idempotency key: a replay carries the version created by the first request. */
public record ClosingClaim(boolean replayed, UUID versionId) { }
