package com.malyah.accountmanager.expenses.application.port;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.StoredExpense;
import com.malyah.accountmanager.expenses.application.StoredExpenseCreation;
import com.malyah.accountmanager.expenses.application.StoredExpensePage;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;

public interface ExpenseRepository {
    StoredExpenseCreation createIdempotently(
            OneOffExpense expense, UUID actorUserId, UUID idempotencyKey, String requestHash, Instant requestedAt);

    StoredExpensePage findBySpace(UUID spaceId, ExpenseListQuery query);

    StoredExpense findById(UUID spaceId, UUID expenseId);

    java.util.List<com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent> history(
            UUID spaceId, UUID expenseId);

    StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, Instant at);

    StoredExpenseCreation settle(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at);

    StoredExpenseCreation reversePayment(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.ReversePaymentCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at);

    StoredExpenseCreation cancel(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CancelExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at);
}
