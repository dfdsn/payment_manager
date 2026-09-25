package com.malyah.accountmanager.expenses.application;

public interface ExpenseUseCase {
    ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command);
    ExpensePage list(String actorEmail, ExpenseListQuery query);
}
