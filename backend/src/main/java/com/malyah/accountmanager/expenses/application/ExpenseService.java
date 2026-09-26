package com.malyah.accountmanager.expenses.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.HashSet;
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
    private final com.malyah.accountmanager.expenses.application.port.CategoryRepository categories;

    public ExpenseService(
            ExpenseRepository repository,
            AuthenticatedUserContextQuery contextQuery,
            ExpenseIdentifierGenerator identifiers,
            Clock clock, com.malyah.accountmanager.identity.application.FinancialMemberAccess memberAccess,
            com.malyah.accountmanager.expenses.application.port.CategoryRepository categories) {
        this.repository = repository;
        this.contextQuery = contextQuery;
        this.identifiers = identifiers;
        this.clock = clock;
        this.memberAccess = memberAccess;
        this.categories = categories;
    }

    public ExpenseService(ExpenseRepository repository, AuthenticatedUserContextQuery contextQuery,
            ExpenseIdentifierGenerator identifiers, Clock clock,
            com.malyah.accountmanager.identity.application.FinancialMemberAccess memberAccess) {
        this(repository, contextQuery, identifiers, clock, memberAccess, null);
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
        if (categories != null) categories.requireSelectable(actor.spaceId(), command.categoryId());
        var expense = new OneOffExpense(
                identifiers.next(), actor.spaceId(), command.description(), ExpenseAmount.parse(command.amount()),
                command.status(), command.dueDate(), command.paymentDate(), null, command.notes(), actor.userId(),
                clock.instant(), payment);
        var stored = categories == null
                ? repository.createIdempotently(expense, actor.userId(), command.idempotencyKey(), fingerprint(expense, null), clock.instant())
                : repository.createIdempotently(expense, actor.userId(), command.idempotencyKey(),
                        fingerprint(expense, command.categoryId()), command.categoryId(), clock.instant());
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

    public BatchSettlementResult settleBatch(String email, BatchSettlementCommand command) {
        if (command == null || command.idempotencyKey() == null)
            throw new ExpenseQueryValidationException("batch", "Informe uma chave de repetição válida.");
        if (!command.confirmed())
            throw new ExpenseQueryValidationException("confirmed", "Confirme a quitação integral do lote.");
        if (command.items() == null || command.items().isEmpty())
            throw new ExpenseQueryValidationException("items", "Selecione ao menos um lançamento.");
        var identifiers = new HashSet<UUID>();
        for (var item : command.items()) {
            if (item == null || item.expenseId() == null || item.version() < 0)
                throw new ExpenseQueryValidationException("items", "Informe lançamentos e versões válidos.");
            if (!identifiers.add(item.expenseId()))
                throw new ExpenseQueryValidationException("items", "Não repita um lançamento no mesmo lote.");
        }
        var payment = new com.malyah.accountmanager.expenses.domain.BatchPaymentInstruction(
                command.paymentDate(), command.paidByUserId());
        var actor = contextQuery.findByEmail(email);
        memberAccess.requireActiveParticipants(actor.spaceId(), actor.userId(), payment.payerId());
        return repository.settleBatch(actor.spaceId(), actor.userId(), command, payment, clock.instant());
    }

    public ExpenseView get(String email, UUID expenseId) {
        if (expenseId == null)
            throw new ExpenseQueryValidationException("expenseId", "Informe a despesa.");
        var actor = contextQuery.findByEmail(email);
        return view(repository.findById(actor.spaceId(), expenseId), actor.timeZone(),
                repository.history(actor.spaceId(), expenseId));
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
        if (categories != null && !Objects.equals(current.categoryId(), command.categoryId()))
            categories.requireSelectable(actor.spaceId(), command.categoryId());
        var corrected = new OneOffExpense(current.id(), current.spaceId(), command.description(),
                ExpenseAmount.parse(command.amount()), command.status(), command.dueDate(), paymentDate, null,
                command.notes(), current.createdByUserId(), current.createdAt(), payment);
        var result = categories == null
                ? repository.correct(actor.spaceId(), actor.userId(), command, corrected, clock.instant())
                : repository.correct(actor.spaceId(), actor.userId(), command, corrected, command.categoryId(), clock.instant());
        return new ExpenseCreationResult(view(result.expense(), actor.timeZone()), result.replayed());
    }

    public ExpenseCreationResult reversePayment(String email, ReversePaymentCommand command) {
        validateAction(command == null ? null : command.expenseId(), command == null ? -1 : command.version(),
                command == null ? null : command.idempotencyKey(), "paymentReversal");
        var actor = contextQuery.findByEmail(email);
        var reason = new com.malyah.accountmanager.expenses.domain.ExpenseActionReason(command.reason());
        var current = repository.findById(actor.spaceId(), command.expenseId());
        if (current.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PAID
                && current.dueDate() == null)
            throw new ExpenseQueryValidationException("dueDate",
                    "Informe o vencimento por correção antes de desfazer esta quitação.");
        var result = repository.reversePayment(actor.spaceId(), actor.userId(), command, reason, clock.instant());
        return new ExpenseCreationResult(view(result.expense(), actor.timeZone()), result.replayed());
    }

    public ExpenseCreationResult cancel(String email, CancelExpenseCommand command) {
        validateAction(command == null ? null : command.expenseId(), command == null ? -1 : command.version(),
                command == null ? null : command.idempotencyKey(), "cancellation");
        var actor = contextQuery.findByEmail(email);
        var reason = new com.malyah.accountmanager.expenses.domain.ExpenseActionReason(command.reason());
        var result = repository.cancel(actor.spaceId(), actor.userId(), command, reason, clock.instant());
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
        return view(expense, timeZone, java.util.List.of());
    }

    private ExpenseView view(StoredExpense expense, String timeZone, java.util.List<ExpenseHistoryEvent> history) {
        var referenceDate = expense.dueDate() == null ? expense.paymentDate() : expense.dueDate();
        var today = LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
        var overdue = expense.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PENDING
                && expense.dueDate().isBefore(today);
        return new ExpenseView(
                expense.id(), "ONE_OFF", expense.description(), expense.amount().toPlainString(), "BRL",
                expense.status(), expense.dueDate(), expense.paymentDate(),
                expense.paidAmount() == null ? null : expense.paidAmount().toPlainString(),
                referenceDate, overdue, expense.categoryName(), expense.categoryId(), null, expense.notes(), expense.createdByUserId(),
                expense.createdByDisplayName(), expense.paidByUserId(), expense.paidByDisplayName(),
                expense.createdAt(), expense.version(), expense.paymentAudit(), history);
    }

    private void validateAction(UUID expenseId, long version, UUID key, String field) {
        if (expenseId == null || key == null || version < 0)
            throw new ExpenseQueryValidationException(field,
                    "Informe a despesa, versão e chave de repetição válidas.");
    }

    private String fingerprint(OneOffExpense expense, UUID categoryId) {
        var canonical = String.join("\u001f",
                expense.description(), expense.amount().canonical(), expense.status().name(),
                value(expense.dueDate()), value(expense.paymentDate()), value(expense.notes()), value(categoryId));
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
