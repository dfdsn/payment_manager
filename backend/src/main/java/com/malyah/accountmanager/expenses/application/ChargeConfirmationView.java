package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.util.UUID;

public record ChargeConfirmationView(String estimatedAmount, Instant confirmedAt, UUID confirmedByUserId,
        String confirmedByDisplayName) { }
