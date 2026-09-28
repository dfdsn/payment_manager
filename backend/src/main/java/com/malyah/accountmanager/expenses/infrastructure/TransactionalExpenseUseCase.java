package com.malyah.accountmanager.expenses.infrastructure;

import java.util.Objects;

import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseCreationResult;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.ExpensePage;
import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;

final class TransactionalExpenseUseCase implements ExpenseUseCase {
    private final ExpenseService delegate;
    private final TransactionTemplate transactions;

    TransactionalExpenseUseCase(ExpenseService delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
    }

    @Override
    public ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.create(actorEmail, command)));
    }

    @Override
    public ExpensePage list(String actorEmail, ExpenseListQuery query) {
        return delegate.list(actorEmail, query);
    }

    @Override
    public com.malyah.accountmanager.expenses.application.ExpenseFilterOptions filterOptions(String actorEmail) {
        return delegate.filterOptions(actorEmail);
    }

    @Override
    public com.malyah.accountmanager.expenses.application.ExpenseView get(
            String actorEmail, java.util.UUID expenseId) {
        return delegate.get(actorEmail, expenseId);
    }

    @Override
    public com.malyah.accountmanager.expenses.application.ExpenseHistoryPage history(
            String actorEmail, java.util.UUID expenseId, int page, int size) {
        return delegate.history(actorEmail, expenseId, page, size);
    }

    @Override
    public ExpenseCreationResult correct(String actorEmail,
            com.malyah.accountmanager.expenses.application.CorrectExpenseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.correct(actorEmail, command)));
    }

    @Override
    public ExpenseCreationResult settle(String actorEmail,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.settle(actorEmail, command)));
    }

    @Override
    public com.malyah.accountmanager.expenses.application.BatchSettlementResult settleBatch(
            String actorEmail,
            com.malyah.accountmanager.expenses.application.BatchSettlementCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.settleBatch(actorEmail, command)));
    }

    @Override
    public ExpenseCreationResult reversePayment(String actorEmail,
            com.malyah.accountmanager.expenses.application.ReversePaymentCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.reversePayment(actorEmail, command)));
    }

    @Override
    public ExpenseCreationResult cancel(String actorEmail,
            com.malyah.accountmanager.expenses.application.CancelExpenseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.cancel(actorEmail, command)));
    }

    @Override
    public ExpenseCreationResult confirmCharge(String actorEmail,
            com.malyah.accountmanager.expenses.application.ConfirmChargeCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.confirmCharge(actorEmail, command)));
    }
}
