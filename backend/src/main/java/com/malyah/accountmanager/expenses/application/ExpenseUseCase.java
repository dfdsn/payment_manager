package com.malyah.accountmanager.expenses.application;

public interface ExpenseUseCase {
    ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command);
    ExpensePage list(String actorEmail, ExpenseListQuery query);
    ExpenseView get(String actorEmail, java.util.UUID expenseId);
    ExpenseCreationResult correct(String actorEmail, CorrectExpenseCommand command);
    ExpenseCreationResult settle(String actorEmail, SettleExpenseCommand command);
}
