package com.malyah.accountmanager.expenses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.port.ExpenseRepository;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;

class ExpenseServiceTest {
    private static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-25T13:00:00Z");

    private ExpenseRepository repository;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExpenseRepository.class);
        AuthenticatedUserContextQuery context = email -> new AuthenticatedUserContext(
                USER, "Pessoa", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR", "America/Sao_Paulo");
        service = new ExpenseService(repository, context, () -> EXPENSE, Clock.fixed(NOW, ZoneOffset.UTC), (space, actor, payer) -> { });
    }

    @Test
    void createsPendingExpenseForAuthenticatedSpaceAndMapsCanonicalMoney() {
        var stored = stored(ExpenseStatus.PENDING, LocalDate.of(2026, 9, 24), null);
        given(repository.createIdempotently(any(), any(), any(), any(), any()))
                .willReturn(new StoredExpenseCreation(stored, false));

        var result = service.create("guest@example.com", new CreateOneOffExpenseCommand(
                "Energia", "150", ExpenseStatus.PENDING, LocalDate.of(2026, 9, 24), null, null, KEY));

        assertThat(result.replayed()).isFalse();
        assertThat(result.expense().amount()).isEqualTo("150.00");
        assertThat(result.expense().currency()).isEqualTo("BRL");
        assertThat(result.expense().overdue()).isTrue();
        assertThat(result.expense().categoryName()).isNull();
        assertThat(result.expense().responsibleUserId()).isNull();
    }

    @Test
    void mapsPaidExpenseAndIdempotentReplay() {
        var stored = stored(ExpenseStatus.PAID, null, LocalDate.of(2026, 9, 25));
        given(repository.createIdempotently(any(), any(), any(), any(), any()))
                .willReturn(new StoredExpenseCreation(stored, true));

        var result = service.create("guest@example.com", new CreateOneOffExpenseCommand(
                "Mercado", "150.00", ExpenseStatus.PAID, null, LocalDate.of(2026, 9, 25), "Pago", KEY));

        assertThat(result.replayed()).isTrue();
        assertThat(result.expense().paymentDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(result.expense().paidAmount()).isEqualTo("150.00");
        assertThat(result.expense().paidByUserId()).isEqualTo(USER);
        assertThat(result.expense().overdue()).isFalse();
    }

    @Test
    void listsStablePageAndCalculatesTotalPages() {
        given(repository.findBySpace(SPACE, new ExpenseListQuery(1, 2, ExpenseSort.AMOUNT, SortDirection.DESC)))
                .willReturn(new StoredExpensePage(List.of(
                        stored(ExpenseStatus.PENDING, LocalDate.of(2026, 9, 25), null)), 5));

        var page = service.list("guest@example.com",
                new ExpenseListQuery(1, 2, ExpenseSort.AMOUNT, SortDirection.DESC));

        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.content()).singleElement().satisfies(expense -> assertThat(expense.overdue()).isFalse());
    }

    @Test
    void rejectsMissingKeyAndInvalidPagination() {
        assertThatThrownBy(() -> service.create("guest@example.com", new CreateOneOffExpenseCommand(
                "Conta", "1.00", ExpenseStatus.PENDING, LocalDate.now(), null, null, null)))
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertInvalidQuery(new ExpenseListQuery(-1, 20, ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
        assertInvalidQuery(new ExpenseListQuery(0, 0, ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
        assertInvalidQuery(new ExpenseListQuery(0, 101, ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
        assertInvalidQuery(new ExpenseListQuery(0, 20, null, SortDirection.ASC));
        assertInvalidQuery(new ExpenseListQuery(0, 20, ExpenseSort.REFERENCE_DATE, null));
        assertThatThrownBy(() -> service.list("guest@example.com", null))
                .isInstanceOf(ExpenseQueryValidationException.class);
    }

    private void assertInvalidQuery(ExpenseListQuery query) {
        assertThatThrownBy(() -> service.list("guest@example.com", query))
                .isInstanceOf(ExpenseQueryValidationException.class);
    }

    @Test void settlesWithTheAuthenticatedActorAndPreservesMoneyAndPayer() {
        given(repository.settle(any(), any(), any(), any(), any()))
                .willReturn(new StoredExpenseCreation(stored(ExpenseStatus.PAID, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 10, 1)), false));
        var command = new SettleExpenseCommand(EXPENSE, 0, "155", LocalDate.of(2026, 10, 1), USER, "Taxa", KEY);
        assertThat(service.settle("guest@example.com", command).expense().status()).isEqualTo(ExpenseStatus.PAID);
        org.mockito.Mockito.verify(repository).settle(org.mockito.ArgumentMatchers.eq(SPACE), org.mockito.ArgumentMatchers.eq(USER),
                org.mockito.ArgumentMatchers.eq(command), org.mockito.ArgumentMatchers.argThat(payment ->
                        payment.amount().canonical().equals("155.00") && payment.payerId().equals(USER)), org.mockito.ArgumentMatchers.eq(NOW));
        for (var invalid : List.of(
                new SettleExpenseCommand(null, 0, "1", LocalDate.now(), USER, null, KEY),
                new SettleExpenseCommand(EXPENSE, -1, "1", LocalDate.now(), USER, null, KEY),
                new SettleExpenseCommand(EXPENSE, 0, "1", LocalDate.now(), USER, null, null)))
            assertThatThrownBy(() -> service.settle("guest@example.com", invalid)).isInstanceOf(ExpenseQueryValidationException.class);
    }

    @Test void correctsPendingAndPaidFieldsWithoutChangingState() {
        var pending = stored(ExpenseStatus.PENDING, LocalDate.of(2026, 9, 24), null);
        given(repository.findById(SPACE, EXPENSE)).willReturn(pending);
        given(repository.correct(any(), any(), any(), any(), any()))
                .willReturn(new StoredExpenseCreation(pending, false));
        var pendingCommand = new CorrectExpenseCommand(EXPENSE, 0, ExpenseStatus.PENDING,
                "Energia corrigida", "151", LocalDate.of(2026, 9, 26), "Ajuste", null, null, null, null, KEY);
        assertThat(service.correct("guest@example.com", pendingCommand).expense().status()).isEqualTo(ExpenseStatus.PENDING);
        org.mockito.Mockito.verify(repository).correct(org.mockito.ArgumentMatchers.eq(SPACE),
                org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(pendingCommand),
                org.mockito.ArgumentMatchers.argThat(expense -> expense.description().equals("Energia corrigida")
                        && expense.amount().canonical().equals("151.00") && expense.payment() == null),
                org.mockito.ArgumentMatchers.eq(NOW));

        var paid = stored(ExpenseStatus.PAID, null, LocalDate.of(2026, 9, 25));
        given(repository.findById(SPACE, EXPENSE)).willReturn(paid);
        given(repository.correct(any(), any(), any(), any(), any()))
                .willReturn(new StoredExpenseCreation(paid, true));
        var paidCommand = new CorrectExpenseCommand(EXPENSE, 0, ExpenseStatus.PAID,
                "Mercado", "150", null, null, "145", LocalDate.of(2026, 9, 26), USER, "Desconto", KEY);
        assertThat(service.correct("guest@example.com", paidCommand).replayed()).isTrue();
    }

    @Test void validatesCorrectionMetadataAndStateSpecificPaymentFields() {
        assertThatThrownBy(() -> service.correct("guest@example.com", new CorrectExpenseCommand(
                EXPENSE, -1, ExpenseStatus.PENDING, "Conta", "1", LocalDate.now(), null,
                null, null, null, null, KEY))).isInstanceOf(ExpenseQueryValidationException.class);
        given(repository.findById(SPACE, EXPENSE)).willReturn(stored(ExpenseStatus.PENDING, LocalDate.now(), null));
        assertThatThrownBy(() -> service.correct("guest@example.com", new CorrectExpenseCommand(
                EXPENSE, 0, ExpenseStatus.PENDING, "Conta", "1", LocalDate.now(), null,
                "1", LocalDate.now(), USER, null, KEY)))
                .isInstanceOf(com.malyah.accountmanager.expenses.domain.ExpenseValidationException.class);
        assertThat(service.get("guest@example.com", EXPENSE).id()).isEqualTo(EXPENSE);
        assertThatThrownBy(() -> service.get("guest@example.com", null))
                .isInstanceOf(ExpenseQueryValidationException.class);
    }

    private StoredExpense stored(ExpenseStatus status, LocalDate dueDate, LocalDate paymentDate) {
        return new StoredExpense(EXPENSE, SPACE, status == ExpenseStatus.PAID ? "Mercado" : "Energia",
                new BigDecimal("150.00"), status, dueDate, paymentDate,
                status == ExpenseStatus.PAID ? new BigDecimal("150.00") : null,
                null, USER, "Pessoa", status == ExpenseStatus.PAID ? USER : null,
                status == ExpenseStatus.PAID ? "Pessoa" : null, NOW, 0);
    }
}
