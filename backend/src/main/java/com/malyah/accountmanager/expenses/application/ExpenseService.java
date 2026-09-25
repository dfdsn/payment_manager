package com.malyah.accountmanager.expenses.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;

import com.malyah.accountmanager.expenses.application.port.ExpenseIdentifierGenerator;
import com.malyah.accountmanager.expenses.application.port.ExpenseRepository;
import com.malyah.accountmanager.expenses.domain.ExpenseAmount;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

public final class ExpenseService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAXIMUM_PAGE_SIZE = 100;

    private final ExpenseRepository repository;
    private final AuthenticatedUserContextQuery contextQuery;
    private final ExpenseIdentifierGenerator identifiers;
    private final Clock clock;

    public ExpenseService(
            ExpenseRepository repository,
            AuthenticatedUserContextQuery contextQuery,
            ExpenseIdentifierGenerator identifiers,
            Clock clock) {
        this.repository = repository;
        this.contextQuery = contextQuery;
        this.identifiers = identifiers;
        this.clock = clock;
    }

    public ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command) {
        Objects.requireNonNull(command);
        if (command.idempotencyKey() == null) {
            throw new ExpenseQueryValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        }
        var actor = contextQuery.findByEmail(actorEmail);
        var expense = new OneOffExpense(
                identifiers.next(), actor.spaceId(), command.description(), ExpenseAmount.parse(command.amount()),
                command.status(), command.dueDate(), command.paymentDate(), null, command.notes(), actor.userId(),
                clock.instant());
        var stored = repository.createIdempotently(
                expense, actor.userId(), command.idempotencyKey(), fingerprint(expense), clock.instant());
        return new ExpenseCreationResult(view(stored.expense(), actor.timeZone()), stored.replayed());
    }

    public ExpensePage list(String actorEmail, ExpenseListQuery query) {
        validate(query);
        var actor = contextQuery.findByEmail(actorEmail);
        var stored = repository.findBySpace(actor.spaceId(), query);
        var totalPages = stored.totalElements() == 0 ? 0
                : Math.toIntExact((stored.totalElements() + query.size() - 1) / query.size());
        return new ExpensePage(
                stored.content().stream().map(expense -> view(expense, actor.timeZone())).toList(),
                query.page(), query.size(), stored.totalElements(), totalPages, query.sort(), query.direction());
    }

    private void validate(ExpenseListQuery query) {
        if (query == null) throw new ExpenseQueryValidationException("page", "Informe a paginação.");
        if (query.page() < 0) throw new ExpenseQueryValidationException("page", "A página não pode ser negativa.");
        if (query.size() < 1 || query.size() > MAXIMUM_PAGE_SIZE) {
            throw new ExpenseQueryValidationException("size", "O tamanho da página deve estar entre 1 e 100.");
        }
        if (query.sort() == null) throw new ExpenseQueryValidationException("sort", "Informe a ordenação.");
        if (query.direction() == null) throw new ExpenseQueryValidationException("direction", "Informe a direção.");
    }

    private ExpenseView view(StoredExpense expense, String timeZone) {
        var referenceDate = expense.dueDate() == null ? expense.paymentDate() : expense.dueDate();
        var today = LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
        var overdue = expense.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PENDING
                && expense.dueDate().isBefore(today);
        return new ExpenseView(
                expense.id(), "ONE_OFF", expense.description(), expense.amount().toPlainString(), "BRL",
                expense.status(), expense.dueDate(), expense.paymentDate(),
                expense.paidAmount() == null ? null : expense.paidAmount().toPlainString(),
                referenceDate, overdue, null, null, expense.notes(), expense.createdByUserId(),
                expense.createdByDisplayName(), expense.paidByUserId(), expense.paidByDisplayName(),
                expense.createdAt(), expense.version());
    }

    private String fingerprint(OneOffExpense expense) {
        var canonical = String.join("\u001f",
                expense.description(), expense.amount().canonical(), expense.status().name(),
                value(expense.dueDate()), value(expense.paymentDate()), value(expense.notes()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }

    private String value(Object value) {
        return value == null ? "<null>" : value.toString();
    }
}
