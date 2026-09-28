package com.malyah.accountmanager.installments.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.malyah.accountmanager.expenses.application.InstallmentAdjuster;
import com.malyah.accountmanager.expenses.application.InstallmentAdjustment;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.port.InstallmentChangeRepository;
import com.malyah.accountmanager.installments.application.port.InstallmentPurchaseRepository;
import com.malyah.accountmanager.installments.domain.InstallmentCancellation;
import com.malyah.accountmanager.installments.domain.InstallmentChange;
import com.malyah.accountmanager.installments.domain.InstallmentChangeScope;
import com.malyah.accountmanager.installments.domain.InstallmentPlan;
import com.malyah.accountmanager.installments.domain.InstallmentState;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;
import com.malyah.accountmanager.installments.domain.PlannedInstallmentUpdate;

/**
 * H05.3: group changes and cancellation of pending installments. Both are previewed without writing and applied only
 * if the impact recalculated under lock matches the reviewed token; paid and cancelled installments are never touched,
 * nothing is refunded and amounts never change (a new total or quantity is a replacement purchase).
 */
public final class InstallmentAdjustmentService implements InstallmentAdjustmentUseCase {
    static final String CHANGE = "CHANGE";
    static final String CANCELLATION = "CANCELLATION";
    private final InstallmentChangeRepository changes;
    private final InstallmentPurchaseRepository purchases;
    private final InstallmentPurchaseService purchaseService;
    private final InstallmentExpenses expenses;
    private final InstallmentAdjuster adjuster;
    private final AuthenticatedUserContextQuery context;
    private final FinancialMemberAccess members;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public InstallmentAdjustmentService(InstallmentChangeRepository changes, InstallmentPurchaseRepository purchases,
            InstallmentPurchaseService purchaseService, InstallmentExpenses expenses, InstallmentAdjuster adjuster,
            AuthenticatedUserContextQuery context, FinancialMemberAccess members, Clock clock,
            Supplier<UUID> identifiers) {
        this.changes = changes; this.purchases = purchases; this.purchaseService = purchaseService;
        this.expenses = expenses; this.adjuster = adjuster; this.context = context; this.members = members;
        this.clock = clock; this.identifiers = identifiers;
    }

    @Override
    public InstallmentImpactView previewChange(String actorEmail, InstallmentChangeCommand command) {
        var change = change(command);
        var actor = context.findByEmail(actorEmail);
        requirePurchase(actor, command.purchaseId());
        requireReferences(actor, change);
        return changeImpact(change, expenses.find(actor.spaceId(), command.purchaseId()));
    }

    @Override
    public InstallmentChangeResult applyChange(String actorEmail, InstallmentChangeCommand command) {
        var change = change(command);
        requireKey(command.idempotencyKey());
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var now = clock.instant();
        var claim = changes.claim(actor.spaceId(), actor.userId(), command.idempotencyKey(),
                hash(String.join("|", command.purchaseId().toString(), CHANGE, String.valueOf(change.fromNumber()),
                        change.scope().name(), String.join(",", change.fields().stream().sorted().toList()),
                        String.valueOf(change.description()), String.valueOf(change.categoryId()),
                        String.valueOf(change.responsibleUserId()), String.valueOf(change.dueDate()),
                        String.valueOf(command.impactToken()))), now);
        if (claim.replayed()) return replay(actor, claim.changeId());
        if (!changes.lockPurchase(actor.spaceId(), command.purchaseId())) throw new InstallmentPurchaseNotFoundException();
        requireReferences(actor, change);
        var snapshots = adjuster.lock(actor.spaceId(), command.purchaseId());
        var impact = changeImpact(change, snapshots);
        requireSameImpact(impact, command.impactToken());
        var byNumber = byNumber(snapshots);
        var changeId = identifiers.get();
        var stored = new StoredInstallmentChange(changeId, command.purchaseId(), CHANGE, impact.affected().size(),
                impact.preserved().size(), null);
        changes.insert(stored, actor.spaceId(), actor.userId(), change.scope().name(), change.fromNumber(),
                String.join(",", InstallmentChange.FIELDS.stream().filter(change.fields()::contains).toList()), null,
                impact.impactToken(), now);
        adjuster.apply(actor.spaceId(), actor.userId(), changeId, now, change.plan(states(snapshots)).stream()
                .map(u -> {
                    var current = byNumber.get(u.number());
                    return new InstallmentAdjustment(current.expenseId(), current.version(), false, null,
                            u.description(), u.dueDate(), u.categoryId(), u.responsibleUserId(), u.changedFields());
                }).toList());
        changes.complete(actor.spaceId(), actor.userId(), command.idempotencyKey(), changeId, now);
        return result(actor, stored, false);
    }

    @Override
    public InstallmentImpactView previewCancellation(String actorEmail, InstallmentCancellationCommand command) {
        var cancellation = cancellation(command);
        var plan = replacementPlan(command);
        var actor = context.findByEmail(actorEmail);
        requirePurchase(actor, command.purchaseId());
        requireReplacementReferences(actor, command);
        return cancellationImpact(cancellation, plan, command.replacement(),
                expenses.find(actor.spaceId(), command.purchaseId()));
    }

    @Override
    public InstallmentChangeResult applyCancellation(String actorEmail, InstallmentCancellationCommand command) {
        var cancellation = cancellation(command);
        var plan = replacementPlan(command);
        requireKey(command.idempotencyKey());
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var now = clock.instant();
        var claim = changes.claim(actor.spaceId(), actor.userId(), command.idempotencyKey(),
                hash(String.join("|", command.purchaseId().toString(), CANCELLATION,
                        cancellation.numbers().toString(), cancellation.reason(), replacementKey(plan, command.replacement()),
                        String.valueOf(command.impactToken()))), now);
        if (claim.replayed()) return replay(actor, claim.changeId());
        if (!changes.lockPurchase(actor.spaceId(), command.purchaseId())) throw new InstallmentPurchaseNotFoundException();
        requireReplacementReferences(actor, command);
        var snapshots = adjuster.lock(actor.spaceId(), command.purchaseId());
        var impact = cancellationImpact(cancellation, plan, command.replacement(), snapshots);
        requireSameImpact(impact, command.impactToken());
        UUID replacementId = null;
        if (plan != null) {
            replacementId = purchaseService.insert(actor, plan, command.replacement().categoryId(),
                    command.replacement().responsibleUserId(), now);
            changes.markReplacement(actor.spaceId(), replacementId, command.purchaseId());
        }
        var changeId = identifiers.get();
        var stored = new StoredInstallmentChange(changeId, command.purchaseId(), CANCELLATION,
                impact.affected().size(), impact.preserved().size(), replacementId);
        changes.insert(stored, actor.spaceId(), actor.userId(), null, null, null, cancellation.reason(),
                impact.impactToken(), now);
        adjuster.apply(actor.spaceId(), actor.userId(), changeId, now, impact.affected().stream()
                .map(a -> InstallmentAdjustment.cancellation(a.expenseId(), a.version(), cancellation.reason()))
                .toList());
        changes.complete(actor.spaceId(), actor.userId(), command.idempotencyKey(), changeId, now);
        return result(actor, stored, false);
    }

    private InstallmentImpactView changeImpact(InstallmentChange change, List<InstallmentExpenseSnapshot> snapshots) {
        var byNumber = byNumber(snapshots);
        var updates = change.plan(states(snapshots));
        var affected = new ArrayList<AffectedInstallmentView>();
        var token = new StringBuilder(CHANGE);
        for (var update : updates) {
            var current = byNumber.get(update.number());
            affected.add(new AffectedInstallmentView(update.number(), current.expenseId(), current.version(),
                    current.amount().toPlainString(), current.dueDate(), fieldChanges(current, update)));
            token.append('|').append(current.expenseId()).append(':').append(current.version()).append(':')
                    .append(update.description()).append(':').append(update.dueDate()).append(':')
                    .append(update.categoryId()).append(':').append(update.responsibleUserId());
        }
        var touched = updates.stream().map(PlannedInstallmentUpdate::number).collect(Collectors.toSet());
        var preserved = preserved(snapshots, touched, s -> {
            if (s.number() < change.fromNumber()) return "BEFORE_START";
            if (change.scope() == InstallmentChangeScope.THIS && s.number() != change.fromNumber()) return "OUTSIDE_SCOPE";
            return "UNCHANGED";
        });
        return new InstallmentImpactView(CHANGE, hash(token.toString()), affected, preserved, amount(affected), null);
    }

    private InstallmentImpactView cancellationImpact(InstallmentCancellation cancellation, InstallmentPlan plan,
            InstallmentPurchaseCommand replacement, List<InstallmentExpenseSnapshot> snapshots) {
        var byNumber = byNumber(snapshots);
        var selected = cancellation.select(states(snapshots));
        var affected = new ArrayList<AffectedInstallmentView>();
        var token = new StringBuilder(CANCELLATION).append('|').append(cancellation.reason()).append('|')
                .append(replacementKey(plan, replacement));
        for (var number : selected) {
            var current = byNumber.get(number);
            affected.add(new AffectedInstallmentView(number, current.expenseId(), current.version(),
                    current.amount().toPlainString(), current.dueDate(),
                    List.of(new InstallmentFieldChangeView("status", ExpenseStatus.PENDING.name(),
                            ExpenseStatus.CANCELLED.name()))));
            token.append('|').append(current.expenseId()).append(':').append(current.version());
        }
        var preserved = preserved(snapshots, new HashSet<>(selected), s -> "NOT_SELECTED");
        return new InstallmentImpactView(CANCELLATION, hash(token.toString()), affected, preserved, amount(affected),
                plan == null ? null : purchaseService.previewOf(plan));
    }

    private static List<PreservedInstallmentView> preserved(List<InstallmentExpenseSnapshot> snapshots,
            Set<Integer> touched, Function<InstallmentExpenseSnapshot, String> pendingReason) {
        return snapshots.stream().filter(s -> !touched.contains(s.number()))
                .map(s -> new PreservedInstallmentView(s.number(), s.status(),
                        s.status() == ExpenseStatus.PENDING ? pendingReason.apply(s) : s.status().name()))
                .toList();
    }

    private static List<InstallmentFieldChangeView> fieldChanges(InstallmentExpenseSnapshot current,
            PlannedInstallmentUpdate update) {
        var result = new ArrayList<InstallmentFieldChangeView>();
        for (var field : update.changedFields()) {
            result.add(switch (field) {
                case "description" -> new InstallmentFieldChangeView(field, current.description(), update.description());
                case "categoryId" -> new InstallmentFieldChangeView(field, text(current.categoryId()), text(update.categoryId()));
                case "responsibleUserId" -> new InstallmentFieldChangeView(field, text(current.responsibleUserId()),
                        text(update.responsibleUserId()));
                default -> new InstallmentFieldChangeView(field, text(current.dueDate()), text(update.dueDate()));
            });
        }
        return result;
    }

    private InstallmentChangeResult replay(AuthenticatedUserContext actor, UUID changeId) {
        var stored = changes.find(actor.spaceId(), changeId).orElseThrow(InstallmentIdempotencyConflictException::new);
        return result(actor, stored, true);
    }

    private InstallmentChangeResult result(AuthenticatedUserContext actor, StoredInstallmentChange stored,
            boolean replayed) {
        return new InstallmentChangeResult(stored.id(), stored.changeType(), stored.affectedCount(),
                stored.preservedCount(), purchaseService.view(actor, stored.purchaseId()),
                stored.replacementPurchaseId() == null ? null : purchaseService.view(actor, stored.replacementPurchaseId()),
                replayed);
    }

    private void requirePurchase(AuthenticatedUserContext actor, UUID purchaseId) {
        if (purchases.find(actor.spaceId(), purchaseId).isEmpty()) throw new InstallmentPurchaseNotFoundException();
    }

    private void requireReferences(AuthenticatedUserContext actor, InstallmentChange change) {
        // A cleared category or responsible needs no check; a new one must be selectable in the actor's space.
        var category = change.fields().contains("categoryId") ? change.categoryId() : null;
        var responsible = change.fields().contains("responsibleUserId") ? change.responsibleUserId() : null;
        purchaseService.requireReferences(actor, category, responsible);
    }

    private void requireReplacementReferences(AuthenticatedUserContext actor, InstallmentCancellationCommand command) {
        if (command.replacement() != null) purchaseService.requireReferences(actor, command.replacement().categoryId(),
                command.replacement().responsibleUserId());
    }

    private static void requireSameImpact(InstallmentImpactView impact, String reviewed) {
        if (!impact.impactToken().equals(reviewed)) throw new InstallmentImpactChangedException();
    }

    private static void requireKey(UUID key) {
        if (key == null) throw new InstallmentValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
    }

    private static InstallmentChange change(InstallmentChangeCommand command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.purchaseId());
        return new InstallmentChange(command.fromNumber(), command.scope(), Set.copyOf(command.changedFields()),
                command.description(), command.categoryId(), command.responsibleUserId(), command.dueDate());
    }

    private static InstallmentCancellation cancellation(InstallmentCancellationCommand command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.purchaseId());
        return new InstallmentCancellation(command.installmentNumbers(), command.reason());
    }

    private static InstallmentPlan replacementPlan(InstallmentCancellationCommand command) {
        return command.replacement() == null ? null : InstallmentPurchaseService.plan(command.replacement());
    }

    private static String replacementKey(InstallmentPlan plan, InstallmentPurchaseCommand replacement) {
        return plan == null ? "no-replacement" : String.join(":", plan.description(), plan.total().toPlainString(),
                String.valueOf(plan.count()), plan.firstDueDate().toString(), String.valueOf(replacement.categoryId()),
                String.valueOf(replacement.responsibleUserId()));
    }

    private static List<InstallmentState> states(List<InstallmentExpenseSnapshot> snapshots) {
        return snapshots.stream().map(s -> new InstallmentState(s.number(), s.status() == ExpenseStatus.PENDING,
                s.dueDate(), s.description(), s.categoryId(), s.responsibleUserId())).toList();
    }

    private static Map<Integer, InstallmentExpenseSnapshot> byNumber(List<InstallmentExpenseSnapshot> snapshots) {
        return snapshots.stream().collect(Collectors.toMap(InstallmentExpenseSnapshot::number, Function.identity()));
    }

    private static String amount(List<AffectedInstallmentView> affected) {
        return InstallmentPurchaseService.sum(affected.stream().map(a -> new BigDecimal(a.amount())).toList());
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
