package com.malyah.accountmanager.expenses.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Estimate replaced by the confirmation, with its author and instant. */
public record ChargeConfirmationAudit(BigDecimal estimatedAmount, Instant confirmedAt, UUID confirmedByUserId,
        String confirmedByDisplayName) { }
