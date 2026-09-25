package com.malyah.accountmanager.expenses.application.port;

import java.util.UUID;

@FunctionalInterface
public interface ExpenseIdentifierGenerator {
    UUID next();
}
