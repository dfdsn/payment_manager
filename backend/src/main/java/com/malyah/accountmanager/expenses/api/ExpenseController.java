package com.malyah.accountmanager.expenses.api;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.expenses.application.CreateOneOffExpenseCommand;
import com.malyah.accountmanager.expenses.application.ExpenseListQuery;
import com.malyah.accountmanager.expenses.application.ExpensePage;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.expenses.application.ExpenseView;
import com.malyah.accountmanager.expenses.application.SortDirection;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/expenses")
class ExpenseController {
    private final ExpenseUseCase useCase;

    ExpenseController(ExpenseUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    ResponseEntity<ExpenseView> create(
            Principal principal,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateExpenseRequest request) {
        var result = useCase.create(principal.getName(), new CreateOneOffExpenseCommand(
                request.description(), request.amount(), request.status(), request.dueDate(), request.paymentDate(),
                request.notes(), idempotencyKey, request.paidAmount(), request.paidByUserId(), request.paymentNotes(),
                request.categoryId(), request.responsibleUserId()));
        if (result.replayed()) return ResponseEntity.ok(result.expense());
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + result.expense().id()))
                .body(result.expense());
    }

    @GetMapping
    ExpensePage list(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "REFERENCE_DATE") ExpenseSort sort,
            @RequestParam(defaultValue = "ASC") SortDirection direction) {
        return useCase.list(principal.getName(), new ExpenseListQuery(page, size, sort, direction));
    }

    @GetMapping("/{id}")
    ExpenseView get(Principal principal, @org.springframework.web.bind.annotation.PathVariable UUID id) {
        return useCase.get(principal.getName(), id);
    }

    @GetMapping("/{id}/history")
    com.malyah.accountmanager.expenses.application.ExpenseHistoryPage history(
            Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return useCase.history(principal.getName(), id, page, size);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    ExpenseView correct(Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CorrectExpenseRequest request) {
        return useCase.correct(principal.getName(), new com.malyah.accountmanager.expenses.application.CorrectExpenseCommand(
                id, request.version(), request.status(), request.description(), request.amount(), request.dueDate(),
                request.notes(), request.paidAmount(), request.paymentDate(), request.paidByUserId(),
                request.paymentNotes(), key, request.categoryId(), request.responsibleUserId())).expense();
    }

    @PostMapping("/{id}/payment")
    ExpenseView settle(Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PaymentRequest request) {
        return useCase.settle(principal.getName(), new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                id, request.version(), request.paidAmount(), request.paymentDate(),
                request.paidByUserId(), request.paymentNotes(), key)).expense();
    }

    @PostMapping("/batch-payment")
    com.malyah.accountmanager.expenses.application.BatchSettlementResult settleBatch(
            Principal principal,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody BatchPaymentRequest request) {
        var items = request.items().stream()
                .map(item -> new com.malyah.accountmanager.expenses.application.BatchSettlementItem(
                        item.expenseId(), item.version()))
                .toList();
        return useCase.settleBatch(principal.getName(),
                new com.malyah.accountmanager.expenses.application.BatchSettlementCommand(
                        items, request.paymentDate(), request.paidByUserId(), request.confirmed(), key));
    }

    @PostMapping("/{id}/payment-reversal")
    ExpenseView reversePayment(Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ExpenseActionRequest request) {
        return useCase.reversePayment(principal.getName(),
                new com.malyah.accountmanager.expenses.application.ReversePaymentCommand(
                        id, request.version(), request.reason(), key)).expense();
    }

    @PostMapping("/{id}/cancellation")
    ExpenseView cancel(Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ExpenseActionRequest request) {
        return useCase.cancel(principal.getName(),
                new com.malyah.accountmanager.expenses.application.CancelExpenseCommand(
                        id, request.version(), request.reason(), key)).expense();
    }

    record PaymentRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
            @jakarta.validation.constraints.NotBlank String paidAmount,
            @jakarta.validation.constraints.NotNull java.time.LocalDate paymentDate,
            @jakarta.validation.constraints.NotNull UUID paidByUserId, String paymentNotes) { }

    record BatchPaymentRequest(
            @jakarta.validation.constraints.NotEmpty java.util.List<@Valid BatchPaymentItemRequest> items,
            @jakarta.validation.constraints.NotNull java.time.LocalDate paymentDate,
            @jakarta.validation.constraints.NotNull UUID paidByUserId,
            @jakarta.validation.constraints.AssertTrue boolean confirmed) { }

    record BatchPaymentItemRequest(
            @jakarta.validation.constraints.NotNull UUID expenseId,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version) { }

    record ExpenseActionRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2000) String reason) { }

    record CorrectExpenseRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
            @jakarta.validation.constraints.NotNull com.malyah.accountmanager.expenses.domain.ExpenseStatus status,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String description,
            @jakarta.validation.constraints.NotBlank String amount,
            java.time.LocalDate dueDate,
            @jakarta.validation.constraints.Size(max = 2000) String notes,
            String paidAmount,
            java.time.LocalDate paymentDate,
            UUID paidByUserId,
            @jakarta.validation.constraints.Size(max = 2000) String paymentNotes,
            UUID categoryId,
            UUID responsibleUserId) { }
}
