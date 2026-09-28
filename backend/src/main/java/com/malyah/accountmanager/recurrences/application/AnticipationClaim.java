package com.malyah.accountmanager.recurrences.application;

import java.util.UUID;

public record AnticipationClaim(boolean replayed, UUID expenseId) { }
