package com.malyah.accountmanager.expenses.application.port;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.StoredExpenseCreation;
import com.malyah.accountmanager.expenses.application.StoredExpensePage;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;

public interface ExpenseRepository {
    StoredExpenseCreation createIdempotently(
            OneOffExpense expense, UUID actorUserId, UUID idempotencyKey, String requestHash, Instant requestedAt);

    StoredExpensePage findBySpace(UUID spaceId, ExpenseListQuery query);
    StoredExpenseCreation settle(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at);
}
