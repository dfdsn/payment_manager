package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.util.UUID;

public record PaymentAudit(UUID recordedByUserId, String recordedByDisplayName, Instant recordedAt, String notes) { }
