package com.malyah.accountmanager.expenses.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

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
    private final com.malyah.accountmanager.identity.application.FinancialMemberAccess memberAccess;

    public ExpenseService(
            ExpenseRepository repository,
            AuthenticatedUserContextQuery contextQuery,
            ExpenseIdentifierGenerator identifiers,
            Clock clock, com.malyah.accountmanager.identity.application.FinancialMemberAccess memberAccess) {
        this.repository = repository;
        this.contextQuery = contextQuery;
        this.identifiers = identifiers;
        this.clock = clock;
        this.memberAccess = memberAccess;
    }

    public ExpenseCreationResult create(String actorEmail, CreateOneOffExpenseCommand command) {
        Objects.requireNonNull(command);
        if (command.idempotencyKey() == null) {
            throw new ExpenseQueryValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        }
        var actor = contextQuery.findByEmail(actorEmail);
        var payment = command.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PAID
                ? new com.malyah.accountmanager.expenses.domain.PaymentDetails(
                        ExpenseAmount.parse(command.paidAmount() == null ? command.amount() : command.paidAmount()),
                        command.paymentDate(), command.paidByUserId() == null ? actor.userId() : command.paidByUserId(),
                        command.paymentNotes()) : null;
        if (payment == null && (command.paidAmount() != null || command.paidByUserId() != null || command.paymentNotes() != null))
            throw new com.malyah.accountmanager.expenses.domain.ExpenseValidationException("payment", "Despesa pendente não possui pagamento.");
        memberAccess.requireActiveParticipants(actor.spaceId(), actor.userId(), payment == null ? null : payment.payerId());
        var expense = new OneOffExpense(
                identifiers.next(), actor.spaceId(), command.description(), ExpenseAmount.parse(command.amount()),
                command.status(), command.dueDate(), command.paymentDate(), null, command.notes(), actor.userId(),
                clock.instant(), payment);
        var stored = repository.createIdempotently(
                expense, actor.userId(), command.idempotencyKey(), fingerprint(expense), clock.instant());
        return new ExpenseCreationResult(view(stored.expense(), actor.timeZone()), stored.replayed());
    }

    public ExpenseCreationResult settle(String email, SettleExpenseCommand command) {
        if (command.idempotencyKey() == null || command.expenseId() == null || command.version() < 0)
            throw new ExpenseQueryValidationException("payment", "Informe a despesa, versão e chave de repetição válidas.");
        var actor = contextQuery.findByEmail(email);
        var payment = new com.malyah.accountmanager.expenses.domain.PaymentDetails(
                ExpenseAmount.parse(command.paidAmount()), command.paymentDate(), command.paidByUserId(), command.paymentNotes());
        memberAccess.requireActiveParticipants(actor.spaceId(), actor.userId(), payment.payerId());
        var result = repository.settle(actor.spaceId(), actor.userId(), command, payment, clock.instant());
        return new ExpenseCreationResult(view(result.expense(), actor.timeZone()), result.replayed());
    }

    public ExpenseView get(String email, UUID expenseId) {
        if (expenseId == null)
            throw new ExpenseQueryValidationException("expenseId", "Informe a despesa.");
        var actor = contextQuery.findByEmail(email);
        return view(repository.findById(actor.spaceId(), expenseId), actor.timeZone());
    }

    public ExpenseCreationResult correct(String email, CorrectExpenseCommand command) {
        if (command.expenseId() == null || command.idempotencyKey() == null || command.status() == null
                || command.version() < 0)
            throw new ExpenseQueryValidationException("correction", "Informe despesa, situação, versão e chave válidas.");
        var actor = contextQuery.findByEmail(email);
        var current = repository.findById(actor.spaceId(), command.expenseId());
        com.malyah.accountmanager.expenses.domain.PaymentDetails payment = null;
        java.time.LocalDate paymentDate = null;
        if (command.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PAID) {
            payment = new com.malyah.accountmanager.expenses.domain.PaymentDetails(
                    ExpenseAmount.parse(command.paidAmount()), command.paymentDate(),
                    command.paidByUserId(), command.paymentNotes());
            paymentDate = command.paymentDate();
        } else if (command.paidAmount() != null || command.paymentDate() != null
                || command.paidByUserId() != null || command.paymentNotes() != null) {
            throw new com.malyah.accountmanager.expenses.domain.ExpenseValidationException(
                    "payment", "Despesa pendente não possui pagamento.");
        }
        memberAccess.requireActiveParticipants(actor.spaceId(), actor.userId(), payment == null ? null : payment.payerId());
        var corrected = new OneOffExpense(current.id(), current.spaceId(), command.description(),
                ExpenseAmount.parse(command.amount()), command.status(), command.dueDate(), paymentDate, null,
                command.notes(), current.createdByUserId(), current.createdAt(), payment);
        var result = repository.correct(actor.spaceId(), actor.userId(), command, corrected, clock.instant());
        return new ExpenseCreationResult(view(result.expense(), actor.timeZone()), result.replayed());
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
                expense.createdAt(), expense.version(), expense.paymentAudit());
    }

    private String fingerprint(OneOffExpense expense) {
        var canonical = String.join("\u001f",
                expense.description(), expense.amount().canonical(), expense.status().name(),
                value(expense.dueDate()), value(expense.paymentDate()), value(expense.notes()));
        var payment = expense.payment();
        // Keep the H02.1 fingerprint for unchanged/default creation requests, including retries after upgrade.
        if (payment != null && (!payment.amount().equals(expense.amount())
                || !payment.payerId().equals(expense.createdByUserId()) || payment.notes() != null))
            canonical += "\u001fPAYMENT:" + payment.canonical();
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
