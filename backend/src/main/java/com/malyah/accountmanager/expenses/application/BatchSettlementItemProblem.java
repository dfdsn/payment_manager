package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record BatchSettlementItemProblem(UUID expenseId, String code, String message) { }
