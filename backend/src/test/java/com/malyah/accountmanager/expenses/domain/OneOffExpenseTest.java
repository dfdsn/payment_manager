package com.malyah.accountmanager.expenses.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OneOffExpenseTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void normalizesMoneyDescriptionNotesAndPendingReference() {
        var expense = expense("  Energia  ", "150", ExpenseStatus.PENDING,
                LocalDate.of(2026, 9, 24), null, "  conta da casa  ");

        assertThat(expense.description()).isEqualTo("Energia");
        assertThat(expense.amount().canonical()).isEqualTo("150.00");
        assertThat(expense.notes()).isEqualTo("conta da casa");
        assertThat(expense.referenceDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(expense.overdueOn(LocalDate.of(2026, 9, 25))).isTrue();
        assertThat(expense.overdueOn(LocalDate.of(2026, 9, 24))).isFalse();
    }

    @Test
    void paidExpenseUsesPaymentDateWhenDueDateIsAbsent() {
        var expense = expense("Mercado", "12.34", ExpenseStatus.PAID,
                null, LocalDate.of(2026, 9, 25), " ");

        assertThat(expense.referenceDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(expense.notes()).isNull();
        assertThat(expense.overdueOn(LocalDate.of(2026, 9, 26))).isFalse();
    }

    @Test
    void enforcesStatusDatesAndTextLimits() {
        assertInvalid("dueDate", () -> expense("Conta", "1", ExpenseStatus.PENDING, null, null, null));
        assertInvalid("paymentDate", () -> expense("Conta", "1", ExpenseStatus.PENDING,
                LocalDate.now(), LocalDate.now(), null));
        assertInvalid("paymentDate", () -> expense("Conta", "1", ExpenseStatus.PAID, null, null, null));
        assertInvalid("description", () -> expense(" ", "1", ExpenseStatus.PENDING,
                LocalDate.now(), null, null));
        assertInvalid("description", () -> expense("x".repeat(201), "1", ExpenseStatus.PENDING,
                LocalDate.now(), null, null));
        assertInvalid("notes", () -> expense("Conta", "1", ExpenseStatus.PENDING,
                LocalDate.now(), null, "x".repeat(2001)));
    }

    @Test
    void enforcesExactPositiveMoneyRange() {
        assertThat(new ExpenseAmount(new BigDecimal("99999999.99")).canonical()).isEqualTo("99999999.99");
        assertInvalid("amount", () -> ExpenseAmount.parse(null));
        assertInvalid("amount", () -> ExpenseAmount.parse("texto"));
        assertInvalid("amount", () -> ExpenseAmount.parse("0"));
        assertInvalid("amount", () -> ExpenseAmount.parse("-1"));
        assertInvalid("amount", () -> ExpenseAmount.parse("1.001"));
        assertInvalid("amount", () -> ExpenseAmount.parse("100000000.00"));
    }

    private OneOffExpense expense(
            String description, String amount, ExpenseStatus status,
            LocalDate dueDate, LocalDate paymentDate, String notes) {
        return new OneOffExpense(ID, SPACE, description, ExpenseAmount.parse(amount), status,
                dueDate, paymentDate, null, notes, USER, Instant.parse("2026-09-25T12:00:00Z"));
    }

    private void assertInvalid(String field, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ExpenseValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo(field));
    }
}
