package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

public record ExpenseFilterPerson(UUID userId, String displayName, boolean activeMember) { }
