package com.malyah.accountmanager.expenses.application;

/** Public contract used by the expenses API and by recurrences when a forecast is confirmed. */
public interface ChargeConfirmationUseCase {
    ExpenseCreationResult confirmCharge(String actorEmail, ConfirmChargeCommand command);
}
