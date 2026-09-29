package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

/** H07.3: new version of a closed month; {@code expectedVersion} is the version in force the member saw. */
public record GenerateVersionCommand(String month, Integer expectedVersion, boolean acknowledgePending,
        UUID idempotencyKey) { }
