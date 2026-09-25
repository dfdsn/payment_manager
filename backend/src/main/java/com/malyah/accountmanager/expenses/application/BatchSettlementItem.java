package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record BatchSettlementItem(UUID expenseId, long version) { }
