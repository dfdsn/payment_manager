package com.malyah.accountmanager.installments.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
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
        requireReferences(actor, command);
        var views = plan.installments().stream().map(i -> new InstallmentView(i.number(), plan.count(),
                i.amount().toPlainString(), i.dueDate(), null, null)).toList();
        return new InstallmentPreviewView(plan.description(), plan.total().toPlainString(), plan.count(),
                plan.firstDueDate(), plan.lastDueDate(), plan.installments().getFirst().amount().toPlainString(),
                plan.installments().getLast().amount().toPlainString(), plan.lastInstallmentAdjustment().toPlainString(),
                sum(plan.installments().stream().map(Installment::amount).toList()), views);
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
        requireReferences(actor, command);
        var purchaseId = identifiers.get();
        repository.insert(purchaseId, actor.spaceId(), plan, command.categoryId(), command.responsibleUserId(),
                actor.userId(), now);
        var entries = plan.installments().stream()
                .map(i -> new InstallmentExpensesCommand.Entry(i.number(), i.amount(), i.dueDate())).toList();
        var created = expenses.create(new InstallmentExpensesCommand(actor.spaceId(), purchaseId, actor.userId(), now,
                plan.description(), command.categoryId(), command.responsibleUserId(), entries));
        if (created.size() != plan.count()) throw new IllegalStateException("Installments were not created.");
        repository.complete(actor.spaceId(), actor.userId(), command.idempotencyKey(), purchaseId, now);
        return new InstallmentPurchaseCreationResult(view(actor, purchaseId), false);
    }

    private static InstallmentPlan plan(InstallmentPurchaseCommand command) {
        return InstallmentPlan.calculate(command.description(), parse(command.totalAmount()),
                command.installmentCount(), command.firstDueDate());
    }

    private void requireReferences(AuthenticatedUserContext actor, InstallmentPurchaseCommand command) {
        if (command.responsibleUserId() != null) {
            try {
                members.requireActiveParticipants(actor.spaceId(), actor.userId(), command.responsibleUserId());
            } catch (AuthenticatedUserContextNotFoundException exception) {
                throw new InstallmentValidationException("responsibleUserId",
                        "O responsável precisa ser membro ativo deste espaço.");
            }
        }
        categories.requireSelectable(actor.spaceId(), command.categoryId());
    }

    private InstallmentPurchaseView view(AuthenticatedUserContext actor, UUID purchaseId) {
        var purchase = repository.find(actor.spaceId(), purchaseId);
        var entries = expenses.find(actor.spaceId(), purchaseId);
        var views = entries.stream().map(e -> new InstallmentView(e.number(), e.count(), e.amount().toPlainString(),
                e.dueDate(), e.expenseId(), e.status())).toList();
        return new InstallmentPurchaseView(purchase.id(), purchase.description(), purchase.totalAmount().toPlainString(),
                purchase.installmentCount(), purchase.firstDueDate(),
                entries.isEmpty() ? null : entries.getLast().dueDate(), purchase.categoryId(), purchase.categoryName(),
                purchase.responsibleUserId(), purchase.responsibleDisplayName(), purchase.createdByUserId(),
                purchase.createdByDisplayName(), purchase.createdAt(),
                sum(entries.stream().map(InstallmentExpenseSnapshot::amount).toList()), views);
    }

    private static String sum(List<BigDecimal> amounts) {
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
