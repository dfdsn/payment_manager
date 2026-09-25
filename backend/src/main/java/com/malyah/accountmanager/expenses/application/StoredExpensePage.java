package com.malyah.accountmanager.expenses.application;

import java.util.List;

public record StoredExpensePage(List<StoredExpense> content, long totalElements) {
}
