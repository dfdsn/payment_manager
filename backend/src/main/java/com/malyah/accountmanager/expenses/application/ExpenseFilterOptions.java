package com.malyah.accountmanager.expenses.application;

import java.util.List;

public record ExpenseFilterOptions(
        List<ExpenseFilterPerson> responsiblePeople,
        List<ExpenseFilterPerson> payerPeople) { }
