package com.malyah.accountmanager.expenses.application.port;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.StoredExpense;
import com.malyah.accountmanager.expenses.application.StoredExpenseCreation;
import com.malyah.accountmanager.expenses.application.StoredExpensePage;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;

public interface ExpenseRepository {
    default StoredExpenseCreation createIdempotently(OneOffExpense expense, UUID actorUserId, UUID idempotencyKey,
            String requestHash, Instant requestedAt) {
        return createIdempotently(expense, actorUserId, idempotencyKey, requestHash, null, requestedAt);
    }
    StoredExpenseCreation createIdempotently(
            OneOffExpense expense, UUID actorUserId, UUID idempotencyKey, String requestHash,
            UUID categoryId, UUID responsibleUserId, Instant requestedAt);

    default StoredExpenseCreation createIdempotently(
            OneOffExpense expense, UUID actorUserId, UUID idempotencyKey, String requestHash,
            UUID categoryId, Instant requestedAt) {
        return createIdempotently(expense, actorUserId, idempotencyKey, requestHash, categoryId, null, requestedAt);
    }

    StoredExpensePage findBySpace(UUID spaceId, ExpenseListQuery query);

    com.malyah.accountmanager.expenses.application.ExpenseFilterOptions filterOptions(UUID spaceId);

    StoredExpense findById(UUID spaceId, UUID expenseId);

    java.util.List<com.malyah.accountmanager.expenses.application.ExpenseHistoryEvent> history(
            UUID spaceId, UUID expenseId);

    StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, UUID categoryId, UUID responsibleUserId, Instant at);

    default StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, UUID categoryId, Instant at) {
        return correct(spaceId, actorId, command, corrected, categoryId, null, at);
    }

    default StoredExpenseCreation correct(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command,
            OneOffExpense corrected, Instant at) {
        return correct(spaceId, actorId, command, corrected, null, at);
    }

    StoredExpenseCreation settle(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.PaymentDetails payment, Instant at);

    com.malyah.accountmanager.expenses.application.BatchSettlementResult settleBatch(
            UUID spaceId,
            UUID actorId,
            com.malyah.accountmanager.expenses.application.BatchSettlementCommand command,
            com.malyah.accountmanager.expenses.domain.BatchPaymentInstruction payment,
            Instant at);

    StoredExpenseCreation reversePayment(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.ReversePaymentCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at);

    StoredExpenseCreation cancel(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.CancelExpenseCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseActionReason reason, Instant at);

    /**
     * Replaces the estimate of a pending recurring charge by the confirmed amount, records the audit event and
     * refreshes later estimates of the same variable recurrence, all in the caller's transaction.
     */
    StoredExpenseCreation confirmCharge(UUID spaceId, UUID actorId,
            com.malyah.accountmanager.expenses.application.ConfirmChargeCommand command,
            com.malyah.accountmanager.expenses.domain.ExpenseAmount amount, Instant at);
}
