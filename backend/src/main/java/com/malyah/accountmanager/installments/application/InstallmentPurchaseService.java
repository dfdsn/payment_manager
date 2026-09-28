package com.malyah.accountmanager.installments.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.InstallmentExpensesCommand;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.port.InstallmentPurchaseRepository;
import com.malyah.accountmanager.installments.domain.Installment;
import com.malyah.accountmanager.installments.domain.InstallmentPlan;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

/**
 * H05.1: creates a purchase and all of its installment entries in one transaction. The preview and the creation use
 * the same calculation; creation recalculates and revalidates everything, so a preview never replaces validation.
 */
public final class InstallmentPurchaseService implements InstallmentPurchaseUseCase {
    static final int MAX_PAGE_SIZE = 100;
    private final InstallmentPurchaseRepository repository;
    private final InstallmentExpenses expenses;
    private final AuthenticatedUserContextQuery context;
    private final CategoryRepository categories;
    private final FinancialMemberAccess members;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public InstallmentPurchaseService(InstallmentPurchaseRepository repository, InstallmentExpenses expenses,
            AuthenticatedUserContextQuery context, CategoryRepository categories, FinancialMemberAccess members,
            Clock clock, Supplier<UUID> identifiers) {
        this.repository = repository; this.expenses = expenses; this.context = context; this.categories = categories;
        this.members = members; this.clock = clock; this.identifiers = identifiers;
    }

    @Override
    public InstallmentPreviewView preview(String actorEmail, InstallmentPurchaseCommand command) {
        Objects.requireNonNull(command);
        var actor = context.findByEmail(actorEmail);
        var plan = plan(command);
        requireReferences(actor, command.categoryId(), command.responsibleUserId());
        return previewOf(plan);
    }

    @Override
    public InstallmentPurchaseCreationResult create(String actorEmail, InstallmentPurchaseCommand command) {
        Objects.requireNonNull(command);
        if (command.idempotencyKey() == null)
            throw new InstallmentValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        var actor = context.findByEmail(actorEmail);
        // Locks the space: serializes with member departure and keeps the author an active participant.
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var plan = plan(command);
        var now = clock.instant();
        var claim = repository.claim(actor.spaceId(), actor.userId(), command.idempotencyKey(),
                fingerprint(plan, command), now);
        if (claim.replayed()) return new InstallmentPurchaseCreationResult(view(actor, claim.purchaseId()), true);
        requireReferences(actor, command.categoryId(), command.responsibleUserId());
        var purchaseId = insert(actor, plan, command.categoryId(), command.responsibleUserId(), now);
        repository.complete(actor.spaceId(), actor.userId(), command.idempotencyKey(), purchaseId, now);
        return new InstallmentPurchaseCreationResult(view(actor, purchaseId), false);
    }

    /** Writes the purchase and all installments; shared by creation and by the replacement of cancelled ones. */
    UUID insert(AuthenticatedUserContext actor, InstallmentPlan plan, UUID categoryId, UUID responsibleUserId,
            Instant now) {
        var purchaseId = identifiers.get();
        repository.insert(purchaseId, actor.spaceId(), plan, categoryId, responsibleUserId, actor.userId(), now);
        var entries = plan.installments().stream()
                .map(i -> new InstallmentExpensesCommand.Entry(i.number(), i.amount(), i.dueDate())).toList();
        var created = expenses.create(new InstallmentExpensesCommand(actor.spaceId(), purchaseId, actor.userId(), now,
                plan.description(), categoryId, responsibleUserId, entries));
        if (created.size() != plan.count()) throw new IllegalStateException("Installments were not created.");
        return purchaseId;
    }

    InstallmentPreviewView previewOf(InstallmentPlan plan) {
        var views = plan.installments().stream().map(i -> new InstallmentView(i.number(), plan.count(),
                i.amount().toPlainString(), i.dueDate(), null, null)).toList();
        return new InstallmentPreviewView(plan.description(), plan.total().toPlainString(), plan.count(),
                plan.firstDueDate(), plan.lastDueDate(), plan.installments().getFirst().amount().toPlainString(),
                plan.installments().getLast().amount().toPlainString(), plan.lastInstallmentAdjustment().toPlainString(),
                sum(plan.installments().stream().map(Installment::amount).toList()), views);
    }

    static InstallmentPlan plan(InstallmentPurchaseCommand command) {
        return InstallmentPlan.calculate(command.description(), parse(command.totalAmount()),
                command.installmentCount(), command.firstDueDate());
    }

    void requireReferences(AuthenticatedUserContext actor, UUID categoryId, UUID responsibleUserId) {
        if (responsibleUserId != null) {
            try {
                members.requireActiveParticipants(actor.spaceId(), actor.userId(), responsibleUserId);
            } catch (AuthenticatedUserContextNotFoundException exception) {
                throw new InstallmentValidationException("responsibleUserId",
                        "O responsável precisa ser membro ativo deste espaço.");
            }
        }
        categories.requireSelectable(actor.spaceId(), categoryId);
    }

    @Override
    public InstallmentPurchasePage list(String actorEmail, int page, int size) {
        if (page < 0) throw new InstallmentValidationException("page", "Informe uma página válida.");
        if (size < 1 || size > MAX_PAGE_SIZE)
            throw new InstallmentValidationException("size", "Informe de 1 a " + MAX_PAGE_SIZE + " compras por página.");
        var actor = context.findByEmail(actorEmail);
        var today = today(actor);
        var purchases = repository.list(actor.spaceId(), Math.multiplyExact(page, size), size);
        var installments = expenses.findByPurchases(actor.spaceId(),
                        purchases.stream().map(StoredInstallmentPurchase::id).toList()).stream()
                .collect(Collectors.groupingBy(InstallmentExpenseSnapshot::purchaseId));
        var items = purchases.stream().map(purchase -> {
            var entries = installments.getOrDefault(purchase.id(), List.of());
            return new InstallmentPurchaseSummary(purchase.id(), purchase.description(),
                    purchase.totalAmount().toPlainString(), purchase.installmentCount(), purchase.firstDueDate(),
                    lastDueDate(entries), purchase.categoryName(), purchase.responsibleDisplayName(),
                    purchase.createdAt(), InstallmentProgress.of(entries, today));
        }).toList();
        return new InstallmentPurchasePage(items, page, size, repository.count(actor.spaceId()));
    }

    @Override
    public InstallmentPurchaseView get(String actorEmail, UUID purchaseId) {
        return view(context.findByEmail(actorEmail), purchaseId);
    }

    InstallmentPurchaseView view(AuthenticatedUserContext actor, UUID purchaseId) {
        var purchase = repository.find(actor.spaceId(), purchaseId)
                .orElseThrow(InstallmentPurchaseNotFoundException::new);
        var entries = expenses.find(actor.spaceId(), purchaseId);
        var today = today(actor);
        var views = entries.stream().map(e -> new InstallmentView(e.number(), e.count(), e.amount().toPlainString(),
                e.dueDate(), e.expenseId(), e.status(), e.version(), InstallmentProgress.isOverdue(e, today),
                e.description(), e.categoryId(), e.categoryName(), e.responsibleUserId(), e.responsibleDisplayName(),
                e.paymentDate(), e.paidAmount() == null ? null : e.paidAmount().toPlainString())).toList();
        return new InstallmentPurchaseView(purchase.id(), purchase.description(), purchase.totalAmount().toPlainString(),
                purchase.installmentCount(), purchase.firstDueDate(), lastDueDate(entries), purchase.categoryId(),
                purchase.categoryName(), purchase.responsibleUserId(), purchase.responsibleDisplayName(),
                purchase.createdByUserId(), purchase.createdByDisplayName(), purchase.createdAt(),
                sum(entries.stream().map(InstallmentExpenseSnapshot::amount).toList()),
                InstallmentProgress.of(entries, today), purchase.replacesPurchaseId(), views);
    }

    LocalDate today(AuthenticatedUserContext actor) {
        return LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
    }

    private static LocalDate lastDueDate(List<InstallmentExpenseSnapshot> entries) {
        return entries.isEmpty() ? null : entries.getLast().dueDate();
    }

    static String sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add).toPlainString();
    }

    private static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank())
            throw new InstallmentValidationException("totalAmount", "Informe o valor total da compra.");
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw new InstallmentValidationException("totalAmount", "Informe um valor decimal válido.");
        }
    }

    private static String fingerprint(InstallmentPlan plan, InstallmentPurchaseCommand command) {
        var value = String.join("|", plan.description(), plan.total().toPlainString(), String.valueOf(plan.count()),
                plan.firstDueDate().toString(), String.valueOf(command.categoryId()),
                String.valueOf(command.responsibleUserId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
