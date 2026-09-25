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
                request.notes(), idempotencyKey, request.paidAmount(), request.paidByUserId(), request.paymentNotes()));
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

    @PostMapping("/{id}/payment")
    ExpenseView settle(Principal principal,
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PaymentRequest request) {
        return useCase.settle(principal.getName(), new com.malyah.accountmanager.expenses.application.SettleExpenseCommand(
                id, request.version(), request.paidAmount(), request.paymentDate(),
                request.paidByUserId(), request.paymentNotes(), key)).expense();
    }

    record PaymentRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
            @jakarta.validation.constraints.NotBlank String paidAmount,
            @jakarta.validation.constraints.NotNull java.time.LocalDate paymentDate,
            @jakarta.validation.constraints.NotNull UUID paidByUserId, String paymentNotes) { }
}
