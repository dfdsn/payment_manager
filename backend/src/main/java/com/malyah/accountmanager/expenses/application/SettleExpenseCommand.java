package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

public record SettleExpenseCommand(UUID expenseId, long version, String paidAmount,
        LocalDate paymentDate, UUID paidByUserId, String paymentNotes, UUID idempotencyKey) { }
