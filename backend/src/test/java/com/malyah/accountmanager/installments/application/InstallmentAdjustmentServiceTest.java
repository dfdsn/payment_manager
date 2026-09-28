package com.malyah.accountmanager.installments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.malyah.accountmanager.expenses.application.InstallmentAdjuster;
import com.malyah.accountmanager.expenses.application.InstallmentAdjustment;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.InstallmentExpensesCommand;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.installments.application.port.InstallmentChangeRepository;
import com.malyah.accountmanager.installments.application.port.InstallmentPurchaseRepository;
import com.malyah.accountmanager.installments.domain.InstallmentChangeScope;
import com.malyah.accountmanager.installments.domain.InstallmentStateConflictException;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

class InstallmentAdjustmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PURCHASE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID RESPONSIBLE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID CHANGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID REPLACEMENT = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final String EMAIL = "ana@example.com";

    private InstallmentChangeRepository changes;
    private InstallmentPurchaseRepository purchases;
    private InstallmentExpenses expenses;
    private InstallmentAdjuster adjuster;
    private CategoryRepository categories;
    private FinancialMemberAccess members;
    private InstallmentAdjustmentService service;
    private List<InstallmentExpenseSnapshot> installments;

    @BeforeEach
    void setup() {
        changes = mock(InstallmentChangeRepository.class);
        purchases = mock(InstallmentPurchaseRepository.class);
        expenses = mock(InstallmentExpenses.class);
        adjuster = mock(InstallmentAdjuster.class);
        categories = mock(CategoryRepository.class);
        members = mock(FinancialMemberAccess.class);
        var context = (com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery) email ->
                new AuthenticatedUserContext(ACTOR, "Ana", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR",
                        "America/Sao_Paulo");
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var ids = new java.util.ArrayDeque<>(List.of(REPLACEMENT, CHANGE_ID));
        var purchaseService = new InstallmentPurchaseService(purchases, expenses, context, categories, members, clock,
                ids::poll);
        service = new InstallmentAdjustmentService(changes, purchases, purchaseService, expenses, adjuster, context,
                members, clock, () -> CHANGE_ID);
        installments = new ArrayList<>(List.of(
                snapshot(1, "33.33", LocalDate.of(2026, 10, 31), ExpenseStatus.PAID),
                snapshot(2, "33.33", LocalDate.of(2026, 11, 30), ExpenseStatus.PENDING),
                snapshot(3, "33.34", LocalDate.of(2026, 12, 31), ExpenseStatus.PENDING),
                snapshot(4, "33.33", LocalDate.of(2027, 1, 31), ExpenseStatus.CANCELLED)));
        when(purchases.find(eq(SPACE), any())).thenAnswer(i -> Optional.of(new StoredInstallmentPurchase(i.getArgument(1),
                SPACE, "Sofá", new BigDecimal("133.33"), 4, LocalDate.of(2026, 10, 31), null, null, null, null, ACTOR,
                "Ana", NOW)));
        when(expenses.find(eq(SPACE), any())).thenAnswer(i -> List.copyOf(installments));
        when(adjuster.lock(SPACE, PURCHASE)).thenAnswer(i -> List.copyOf(installments));
        when(changes.claim(any(), any(), any(), anyString(), any())).thenReturn(new ChangeClaim(false, null));
        when(changes.lockPurchase(SPACE, PURCHASE)).thenReturn(true);
    }

    @Test
    void previewShowsAffectedAndPreservedInstallmentsWithoutWriting() {
        var impact = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description", "categoryId", "responsibleUserId", "dueDate"), null, null));

        assertThat(impact.changeType()).isEqualTo("CHANGE");
        assertThat(impact.impactToken()).hasSize(64);
        assertThat(impact.affected()).extracting(AffectedInstallmentView::number).containsExactly(2, 3);
        assertThat(impact.affected().getFirst()).satisfies(a -> {
            assertThat(a.expenseId()).isEqualTo(id(2)); assertThat(a.version()).isEqualTo(2);
            assertThat(a.amount()).isEqualTo("33.33"); assertThat(a.dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
            assertThat(a.changes()).containsExactly(
                    new InstallmentFieldChangeView("description", "Sofá", "Sofá novo"),
                    new InstallmentFieldChangeView("categoryId", null, CATEGORY.toString()),
                    new InstallmentFieldChangeView("responsibleUserId", null, RESPONSIBLE.toString()),
                    new InstallmentFieldChangeView("dueDate", "2026-11-30", "2026-12-05"));
        });
        assertThat(impact.affected().get(1).changes()).last()
                .isEqualTo(new InstallmentFieldChangeView("dueDate", "2026-12-31", "2027-01-05"));
        assertThat(impact.preserved()).containsExactly(new PreservedInstallmentView(1, ExpenseStatus.PAID, "PAID"),
                new PreservedInstallmentView(4, ExpenseStatus.CANCELLED, "CANCELLED"));
        assertThat(impact.affectedAmount()).isEqualTo("66.67");
        assertThat(impact.replacement()).isNull();
        verify(members).requireActiveParticipants(SPACE, ACTOR, RESPONSIBLE);
        verify(categories).requireSelectable(SPACE, CATEGORY);
        verifyNoInteractions(changes, adjuster);
        // The same impact always gives the same token; another one gives another token.
        assertThat(service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description", "categoryId", "responsibleUserId", "dueDate"), null, null)).impactToken())
                .isEqualTo(impact.impactToken());
        assertThat(service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), null, null))
                .impactToken()).isNotEqualTo(impact.impactToken());
    }

    @Test
    void previewReasonsExplainEveryPendingInstallmentLeftOut() {
        installments.set(0, snapshot(1, "33.33", LocalDate.of(2026, 10, 31), ExpenseStatus.PENDING));
        installments.set(3, snapshot(4, "33.33", LocalDate.of(2027, 1, 31), ExpenseStatus.PENDING));
        var impact = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), null, null));
        assertThat(impact.preserved()).extracting(PreservedInstallmentView::number, PreservedInstallmentView::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "BEFORE_START"),
                        org.assertj.core.groups.Tuple.tuple(3, "OUTSIDE_SCOPE"),
                        org.assertj.core.groups.Tuple.tuple(4, "OUTSIDE_SCOPE"));
        installments.set(2, new InstallmentExpenseSnapshot(id(3), PURCHASE, 3, 4, new BigDecimal("33.34"),
                LocalDate.of(2026, 12, 31), ExpenseStatus.PENDING, 3, "Sofá novo", null, null, null, null, null, null));
        var following = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description"), null, null));
        assertThat(following.preserved()).extracting(PreservedInstallmentView::reason)
                .containsExactly("BEFORE_START", "UNCHANGED");
        installments.set(1, new InstallmentExpenseSnapshot(id(2), PURCHASE, 2, 4, new BigDecimal("33.33"),
                LocalDate.of(2026, 11, 30), ExpenseStatus.PENDING, 2, "Sofá novo", null, null, null, null, null, null));
        installments.set(2, snapshot(3, "33.34", LocalDate.of(2026, 12, 31), ExpenseStatus.PENDING));
        var startUnchanged = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("description"), null, null));
        assertThat(startUnchanged.preserved()).extracting(PreservedInstallmentView::number, PreservedInstallmentView::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "BEFORE_START"),
                        org.assertj.core.groups.Tuple.tuple(2, "UNCHANGED"));
    }

    @Test
    void applyLocksRecalculatesUnderLockAndAppliesTheReviewedImpact() {
        var command = change(2, InstallmentChangeScope.THIS_AND_FOLLOWING, List.of("description", "dueDate"), null, null);
        var token = service.previewChange(EMAIL, command).impactToken();

        var result = service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                List.of("dueDate", "description"), token, KEY));

        var order = inOrder(members, changes, adjuster);
        order.verify(members).requireActiveParticipants(SPACE, ACTOR, null);
        order.verify(changes).claim(eq(SPACE), eq(ACTOR), eq(KEY), anyString(), eq(NOW));
        order.verify(changes).lockPurchase(SPACE, PURCHASE);
        order.verify(adjuster).lock(SPACE, PURCHASE);
        var stored = ArgumentCaptor.forClass(StoredInstallmentChange.class);
        order.verify(changes).insert(stored.capture(), eq(SPACE), eq(ACTOR), eq("THIS_AND_FOLLOWING"), eq(2),
                eq("description,dueDate"), eq(null), eq(token), eq(NOW));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<InstallmentAdjustment>> applied = ArgumentCaptor.forClass(List.class);
        order.verify(adjuster).apply(eq(SPACE), eq(ACTOR), eq(CHANGE_ID), eq(NOW), applied.capture());
        order.verify(changes).complete(SPACE, ACTOR, KEY, CHANGE_ID, NOW);
        assertThat(stored.getValue()).isEqualTo(new StoredInstallmentChange(CHANGE_ID, PURCHASE, "CHANGE", 2, 2, null));
        assertThat(applied.getValue()).containsExactly(
                new InstallmentAdjustment(id(2), 2, false, null, "Sofá novo", LocalDate.of(2026, 12, 5), null, null,
                        List.of("description", "dueDate")),
                new InstallmentAdjustment(id(3), 3, false, null, "Sofá novo", LocalDate.of(2027, 1, 5), null, null,
                        List.of("description", "dueDate")));
        assertThat(result).satisfies(r -> {
            assertThat(r.changeId()).isEqualTo(CHANGE_ID); assertThat(r.changeType()).isEqualTo("CHANGE");
            assertThat(r.affectedCount()).isEqualTo(2); assertThat(r.preservedCount()).isEqualTo(2);
            assertThat(r.purchase().id()).isEqualTo(PURCHASE); assertThat(r.replacement()).isNull();
            assertThat(r.replayed()).isFalse();
        });
    }

    @Test
    void aDifferentImpactUnderLockIsRefusedBeforeWriting() {
        var token = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), null, null))
                .impactToken();
        installments.set(1, snapshot(2, "33.33", LocalDate.of(2026, 11, 30), ExpenseStatus.PENDING, 5));
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                token, KEY))).isInstanceOf(InstallmentImpactChangedException.class).hasMessageContaining("Revise o impacto");
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                null, KEY))).isInstanceOf(InstallmentImpactChangedException.class);
        verify(changes, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(adjuster, never()).apply(any(), any(), any(), any(), any());
        verify(changes, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aReplayReturnsTheStoredResultWithoutLockingOrWriting() {
        when(changes.claim(any(), any(), any(), anyString(), any())).thenReturn(new ChangeClaim(true, CHANGE_ID));
        when(changes.find(SPACE, CHANGE_ID)).thenReturn(Optional.of(new StoredInstallmentChange(CHANGE_ID, PURCHASE,
                "CANCELLATION", 2, 2, REPLACEMENT)));
        var result = service.applyCancellation(EMAIL, cancellation(List.of(2, 3), null, "t", KEY));
        assertThat(result.replayed()).isTrue();
        assertThat(result.affectedCount()).isEqualTo(2);
        assertThat(result.replacement().id()).isEqualTo(REPLACEMENT);
        verify(changes, never()).lockPurchase(any(), any());
        verifyNoInteractions(adjuster);

        when(changes.find(SPACE, CHANGE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                "t", KEY))).isInstanceOf(InstallmentIdempotencyConflictException.class);
    }

    @Test
    void theRequestFingerprintCoversEveryInputAndTheReviewedToken() {
        attempt(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), "b", KEY)));
        attempt(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS_AND_FOLLOWING, List.of("description"), "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, change(3, InstallmentChangeScope.THIS, List.of("description"), "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("dueDate"), "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, new InstallmentChangeCommand(PURCHASE, 2, InstallmentChangeScope.THIS,
                List.of("description"), "Outro", null, null, null, "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, new InstallmentChangeCommand(PURCHASE, 2, InstallmentChangeScope.THIS,
                List.of("categoryId"), null, CATEGORY, null, null, "a", KEY)));
        attempt(() -> service.applyChange(EMAIL, new InstallmentChangeCommand(PURCHASE, 2, InstallmentChangeScope.THIS,
                List.of("responsibleUserId"), null, null, RESPONSIBLE, null, "a", KEY)));
        attempt(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), null, "a", KEY)));
        attempt(() -> service.applyCancellation(EMAIL, cancellation(List.of(2, 3), null, "a", KEY)));
        attempt(() -> service.applyCancellation(EMAIL, new InstallmentCancellationCommand(PURCHASE, List.of(2), "Outro motivo", null,
                "a", KEY)));
        attempt(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), replacement("20.00", 2), "a", KEY)));
        attempt(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), replacement("20.00", 3), "a", KEY)));
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(changes, times(13)).claim(eq(SPACE), eq(ACTOR), eq(KEY), hashes.capture(), eq(NOW));
        assertThat(hashes.getAllValues()).doesNotHaveDuplicates().allSatisfy(h -> assertThat(h).hasSize(64));
    }

    @Test
    void cancellationWithReplacementCreatesTheNewPurchaseLinksItAndCancelsTheSelection() {
        when(expenses.create(any())).thenAnswer(i -> {
            InstallmentExpensesCommand c = i.getArgument(0);
            return c.entries().stream().map(e -> snapshot(e.number(), e.amount().toPlainString(), e.dueDate(),
                    ExpenseStatus.PENDING)).toList();
        });
        var command = cancellation(List.of(3, 2), replacement("70.00", 4), null, null);
        var impact = service.previewCancellation(EMAIL, command);
        assertThat(impact.changeType()).isEqualTo("CANCELLATION");
        assertThat(impact.affected()).extracting(AffectedInstallmentView::number).containsExactly(2, 3);
        assertThat(impact.affected().getFirst().changes())
                .containsExactly(new InstallmentFieldChangeView("status", "PENDING", "CANCELLED"));
        assertThat(impact.preserved()).extracting(PreservedInstallmentView::reason).containsExactly("PAID", "CANCELLED");
        assertThat(impact.affectedAmount()).isEqualTo("66.67");
        assertThat(impact.replacement().installments()).extracting(InstallmentView::amount)
                .containsExactly("17.50", "17.50", "17.50", "17.50");
        verify(categories).requireSelectable(SPACE, CATEGORY);
        verifyNoInteractions(changes);
        // Another replacement is another impact: the reviewed token covers it.
        assertThat(service.previewCancellation(EMAIL, cancellation(List.of(2, 3), replacement("70.00", 5), null, null))
                .impactToken()).isNotEqualTo(impact.impactToken());
        assertThat(service.previewCancellation(EMAIL, cancellation(List.of(2, 3), null, null, null)).impactToken())
                .isNotEqualTo(impact.impactToken());

        var result = service.applyCancellation(EMAIL, cancellation(List.of(2, 3), replacement("70.00", 4),
                impact.impactToken(), KEY));

        var order = inOrder(members, changes, adjuster, purchases, expenses);
        order.verify(members).requireActiveParticipants(SPACE, ACTOR, null);
        order.verify(changes).claim(eq(SPACE), eq(ACTOR), eq(KEY), anyString(), eq(NOW));
        order.verify(changes).lockPurchase(SPACE, PURCHASE);
        order.verify(adjuster).lock(SPACE, PURCHASE);
        order.verify(purchases).insert(eq(REPLACEMENT), eq(SPACE), any(), eq(CATEGORY), eq(null), eq(ACTOR), eq(NOW));
        order.verify(expenses).create(any());
        order.verify(changes).markReplacement(SPACE, REPLACEMENT, PURCHASE);
        order.verify(changes).insert(new StoredInstallmentChange(CHANGE_ID, PURCHASE, "CANCELLATION", 2, 2, REPLACEMENT),
                SPACE, ACTOR, null, null, null, "Troca", impact.impactToken(), NOW);
        order.verify(adjuster).apply(SPACE, ACTOR, CHANGE_ID, NOW, List.of(
                InstallmentAdjustment.cancellation(id(2), 2, "Troca"), InstallmentAdjustment.cancellation(id(3), 3, "Troca")));
        order.verify(changes).complete(SPACE, ACTOR, KEY, CHANGE_ID, NOW);
        assertThat(result.replacement().id()).isEqualTo(REPLACEMENT);
        assertThat(result.changeType()).isEqualTo("CANCELLATION");
    }

    @Test
    void cancellationWithoutReplacementOnlyCancels() {
        var token = service.previewCancellation(EMAIL, cancellation(List.of(2), null, null, null)).impactToken();
        var result = service.applyCancellation(EMAIL, cancellation(List.of(2), null, token, KEY));
        assertThat(result.replacement()).isNull();
        assertThat(service.previewCancellation(EMAIL, cancellation(List.of(2), null, null, null)).preserved())
                .extracting(PreservedInstallmentView::number, PreservedInstallmentView::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "PAID"),
                        org.assertj.core.groups.Tuple.tuple(3, "NOT_SELECTED"),
                        org.assertj.core.groups.Tuple.tuple(4, "CANCELLED"));
        verify(changes, never()).markReplacement(any(), any(), any());
        verify(purchases, never()).insert(any(), any(), any(), any(), any(), any(), any());
        verify(changes).insert(new StoredInstallmentChange(CHANGE_ID, PURCHASE, "CANCELLATION", 1, 3, null), SPACE,
                ACTOR, null, null, null, "Troca", token, NOW);
    }

    @Test
    void invalidRequestsMissingKeysAndUnknownPurchasesStopEarly() {
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                "t", null))).isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("Idempotency-Key");
        assertThatThrownBy(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), null, "t", null)))
                .extracting("field").isEqualTo("Idempotency-Key");
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of(), "t", KEY)))
                .extracting("field").isEqualTo("changedFields");
        assertThatThrownBy(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), replacement("0.01", 2), "t", KEY)))
                .extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> service.applyChange(EMAIL, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.applyCancellation(EMAIL, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(changes, adjuster, members);

        when(changes.lockPurchase(SPACE, PURCHASE)).thenReturn(false);
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                "t", KEY))).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> service.applyCancellation(EMAIL, cancellation(List.of(2), null, "t", KEY)))
                .isInstanceOf(InstallmentPurchaseNotFoundException.class);
        when(purchases.find(SPACE, PURCHASE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"),
                null, null))).isInstanceOf(InstallmentPurchaseNotFoundException.class);
        assertThatThrownBy(() -> service.previewCancellation(EMAIL, cancellation(List.of(2), null, null, null)))
                .isInstanceOf(InstallmentPurchaseNotFoundException.class);
        verifyNoInteractions(adjuster);
    }

    @Test
    void paidOrCancelledInstallmentsCannotBeTargeted() {
        assertThatThrownBy(() -> service.previewCancellation(EMAIL, cancellation(List.of(1, 2), null, null, null)))
                .isInstanceOf(InstallmentStateConflictException.class);
        assertThatThrownBy(() -> service.applyChange(EMAIL, change(4, InstallmentChangeScope.THIS, List.of("description"),
                "t", KEY))).isInstanceOf(InstallmentStateConflictException.class);
        verify(adjuster, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void untouchedReferencesAreNotChecked() {
        service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("description"), null, null));
        verify(members, never()).requireActiveParticipants(any(), any(), any());
        verify(categories).requireSelectable(SPACE, null);
        service.previewCancellation(EMAIL, cancellation(List.of(2), null, null, null));
        verifyNoMoreInteractions(categories);
    }

    @Test
    void applyChecksReferencesAgainUnderTheLockAndAChangeReplayReturnsTheStoredResult() {
        var token = service.previewChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("categoryId"), null, null))
                .impactToken();
        clearInvocations(categories, members);
        service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("categoryId"), token, KEY));
        var order = inOrder(changes, categories, adjuster);
        order.verify(changes).lockPurchase(SPACE, PURCHASE);
        order.verify(categories).requireSelectable(SPACE, CATEGORY);
        order.verify(adjuster).lock(SPACE, PURCHASE);

        var cancelToken = service.previewCancellation(EMAIL, cancellation(List.of(2), replacement("20.00", 2), null, null))
                .impactToken();
        clearInvocations(categories, changes, adjuster);
        when(expenses.create(any())).thenAnswer(i -> {
            InstallmentExpensesCommand c = i.getArgument(0);
            return c.entries().stream().map(e -> snapshot(e.number(), e.amount().toPlainString(), e.dueDate(),
                    ExpenseStatus.PENDING)).toList();
        });
        service.applyCancellation(EMAIL, cancellation(List.of(2), replacement("20.00", 2), cancelToken, KEY));
        var cancelOrder = inOrder(changes, categories, adjuster);
        cancelOrder.verify(changes).lockPurchase(SPACE, PURCHASE);
        cancelOrder.verify(categories).requireSelectable(SPACE, CATEGORY);
        cancelOrder.verify(adjuster).lock(SPACE, PURCHASE);

        when(changes.claim(any(), any(), any(), anyString(), any())).thenReturn(new ChangeClaim(true, CHANGE_ID));
        when(changes.find(SPACE, CHANGE_ID)).thenReturn(Optional.of(new StoredInstallmentChange(CHANGE_ID, PURCHASE,
                "CHANGE", 1, 3, null)));
        clearInvocations(changes, adjuster);
        var replay = service.applyChange(EMAIL, change(2, InstallmentChangeScope.THIS, List.of("categoryId"), token, KEY));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.changeType()).isEqualTo("CHANGE");
        assertThat(replay.affectedCount()).isEqualTo(1);
        verify(changes, never()).lockPurchase(any(), any());
        verifyNoInteractions(adjuster);
    }

    /** The claim happens before the impact check; a stale token then stops the request, which is fine here. */
    private static void attempt(Runnable call) {
        try { call.run(); } catch (InstallmentImpactChangedException expected) { /* claim already recorded */ }
    }

    private static InstallmentChangeCommand change(int from, InstallmentChangeScope scope, List<String> fields,
            String token, UUID key) {
        return new InstallmentChangeCommand(PURCHASE, from, scope, fields, "Sofá novo", CATEGORY, RESPONSIBLE,
                LocalDate.of(2026, 12, 5), token, key);
    }

    private static InstallmentCancellationCommand cancellation(List<Integer> numbers, InstallmentPurchaseCommand replacement,
            String token, UUID key) {
        return new InstallmentCancellationCommand(PURCHASE, numbers, "Troca", replacement, token, key);
    }

    private static InstallmentPurchaseCommand replacement(String total, int count) {
        return new InstallmentPurchaseCommand("Sofá (restante)", total, count, LocalDate.of(2026, 11, 30), CATEGORY, null,
                null);
    }

    private static UUID id(int number) { return UUID.fromString("00000000-0000-0000-0001-00000000000" + number); }

    private static InstallmentExpenseSnapshot snapshot(int number, String amount, LocalDate due, ExpenseStatus status) {
        return snapshot(number, amount, due, status, number);
    }

    private static InstallmentExpenseSnapshot snapshot(int number, String amount, LocalDate due, ExpenseStatus status,
            long version) {
        return new InstallmentExpenseSnapshot(id(number), PURCHASE, number, 4, new BigDecimal(amount), due, status,
                version, "Sofá", null, null, null, null, null, null);
    }
}
