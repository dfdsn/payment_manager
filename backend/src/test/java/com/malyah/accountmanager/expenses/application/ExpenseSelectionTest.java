package com.malyah.accountmanager.expenses.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExpenseSelectionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private static ExpenseSelection search(String text) {
        return new ExpenseSelection(text, TODAY, TODAY, ExpenseDateBasis.DUE_DATE, null, false, null, false, null,
                ExpenseStatusFilter.ACTIVE, TODAY);
    }

    @Test
    void searchLimitCountsTrimmedCharactersAndAcceptsExactlyTheLimit() {
        assertThatCode(() -> search("a".repeat(200)).validate()).doesNotThrowAnyException();
        assertThatCode(() -> search("  " + "a".repeat(200) + "  ").validate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> search("a".repeat(201)).validate())
                .isInstanceOf(ExpenseQueryValidationException.class).hasMessageContaining("200");
    }

    @Test
    void periodAndExclusiveFiltersAreValidated() {
        assertThatCode(() -> search(null).withPeriod(TODAY, TODAY).validate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> search(null).withPeriod(TODAY.plusDays(1), TODAY).validate())
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> new ExpenseSelection(null, null, null, ExpenseDateBasis.DUE_DATE, UUID.randomUUID(),
                true, null, false, null, null, TODAY).validate()).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> new ExpenseSelection(null, null, null, ExpenseDateBasis.DUE_DATE, null, false,
                UUID.randomUUID(), true, null, null, TODAY).validate())
                .isInstanceOf(ExpenseQueryValidationException.class);
    }
}
