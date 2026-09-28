package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record ConfirmChargeCommand(UUID expenseId, long version, String confirmedAmount, UUID idempotencyKey) { }
