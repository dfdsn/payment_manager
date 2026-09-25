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
    public ExpenseCreationResult settle(String actorEmail,
            com.malyah.accountmanager.expenses.application.SettleExpenseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.settle(actorEmail, command)));
    }
}
