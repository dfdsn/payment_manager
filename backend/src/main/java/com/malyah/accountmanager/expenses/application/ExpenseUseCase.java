package com.malyah.accountmanager.expenses.application;

public interface ExpenseUseCase {
    ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command);
    ExpensePage list(String actorEmail, ExpenseListQuery query);
    ExpenseView get(String actorEmail, java.util.UUID expenseId);
    ExpenseHistoryPage history(String actorEmail, java.util.UUID expenseId, int page, int size);
    ExpenseCreationResult correct(String actorEmail, CorrectExpenseCommand command);
    ExpenseCreationResult settle(String actorEmail, SettleExpenseCommand command);
    BatchSettlementResult settleBatch(String actorEmail, BatchSettlementCommand command);
    ExpenseCreationResult reversePayment(String actorEmail, ReversePaymentCommand command);
    ExpenseCreationResult cancel(String actorEmail, CancelExpenseCommand command);
}
