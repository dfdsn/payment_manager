package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record BatchSettlementItemResult(
        UUID expenseId,
        long fromVersion,
        long toVersion,
        String paidAmount) { }
