package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record ReversePaymentCommand(
        UUID expenseId, long version, String reason, UUID idempotencyKey) { }
