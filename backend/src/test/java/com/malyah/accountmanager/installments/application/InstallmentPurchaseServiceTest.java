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
import com.malyah.accountmanager.expenses.application.CategoryConflictException;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.InstallmentExpensesCommand;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.installments.application.port.InstallmentPurchaseRepository;
import com.malyah.accountmanager.installments.domain.InstallmentPlan;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

class InstallmentPurchaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PURCHASE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID RESPONSIBLE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String EMAIL = "ana@example.com";

    private InstallmentPurchaseRepository repository;
    private InstallmentExpenses expenses;
    private CategoryRepository categories;
    private FinancialMemberAccess members;
    private InstallmentPurchaseService service;
    private final List<InstallmentExpenseSnapshot> created = new ArrayList<>();

    @BeforeEach
    void setup() {
        repository = mock(InstallmentPurchaseRepository.class);
        expenses = mock(InstallmentExpenses.class);
        categories = mock(CategoryRepository.class);
        members = mock(FinancialMemberAccess.class);
        service = new InstallmentPurchaseService(repository, expenses, email -> new AuthenticatedUserContext(ACTOR, "Ana",
                email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR", "America/Sao_Paulo"), categories, members,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> PURCHASE);
        when(repository.claim(any(), any(), any(), anyString(), any())).thenReturn(new PurchaseClaim(false, null));
        when(repository.find(SPACE, PURCHASE)).thenReturn(Optional.of(stored(PURCHASE)));
        when(expenses.create(any())).thenAnswer(invocation -> {
            InstallmentExpensesCommand command = invocation.getArgument(0);
            created.clear();
            command.entries().forEach(e -> created.add(snapshot(PURCHASE, e.number(), command.entries().size(),
                    e.amount().toPlainString(), e.dueDate(), ExpenseStatus.PENDING)));
            return List.copyOf(created);
        });
        when(expenses.find(SPACE, PURCHASE)).thenAnswer(invocation -> List.copyOf(created));
    }

    @Test
    void previewUsesTheBackendCalculationAndWritesNothing() {
        var preview = service.preview(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), null));

        assertThat(preview.totalAmount()).isEqualTo("100.00");
        assertThat(preview.regularAmount()).isEqualTo("33.33");
        assertThat(preview.lastAmount()).isEqualTo("33.34");
        assertThat(preview.lastInstallmentAdjustment()).isEqualTo("0.01");
        assertThat(preview.installmentsSum()).isEqualTo("100.00");
        assertThat(preview.firstDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(preview.lastDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(preview.installments()).extracting(InstallmentView::dueDate).containsExactly(LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31));
        assertThat(preview.installments()).allSatisfy(i -> {
            assertThat(i.count()).isEqualTo(3); assertThat(i.expenseId()).isNull(); assertThat(i.status()).isNull();
        });
        verify(members).requireActiveParticipants(SPACE, ACTOR, RESPONSIBLE);
        verify(categories).requireSelectable(SPACE, CATEGORY);
        verifyNoInteractions(repository, expenses);
    }

    @Test
    void createsPurchaseAndInstallmentsInOrderAndReportsTheLinkedEntries() {
        var result = service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY));

        var order = inOrder(members, repository, categories, expenses);
        order.verify(members).requireActiveParticipants(SPACE, ACTOR, null);
        var hash = ArgumentCaptor.forClass(String.class);
        order.verify(repository).claim(eq(SPACE), eq(ACTOR), eq(KEY), hash.capture(), eq(NOW));
        order.verify(members).requireActiveParticipants(SPACE, ACTOR, RESPONSIBLE);
        order.verify(categories).requireSelectable(SPACE, CATEGORY);
        var plan = ArgumentCaptor.forClass(InstallmentPlan.class);
        order.verify(repository).insert(eq(PURCHASE), eq(SPACE), plan.capture(), eq(CATEGORY), eq(RESPONSIBLE), eq(ACTOR), eq(NOW));
        var entries = ArgumentCaptor.forClass(InstallmentExpensesCommand.class);
        order.verify(expenses).create(entries.capture());
        order.verify(repository).complete(SPACE, ACTOR, KEY, PURCHASE, NOW);

        assertThat(hash.getValue()).hasSize(64);
        assertThat(plan.getValue().total()).isEqualTo(new BigDecimal("100.00"));
        assertThat(entries.getValue()).satisfies(c -> {
            assertThat(c.spaceId()).isEqualTo(SPACE); assertThat(c.purchaseId()).isEqualTo(PURCHASE);
            assertThat(c.createdByUserId()).isEqualTo(ACTOR); assertThat(c.createdAt()).isEqualTo(NOW);
            assertThat(c.description()).isEqualTo("Sofá"); assertThat(c.categoryId()).isEqualTo(CATEGORY);
            assertThat(c.responsibleUserId()).isEqualTo(RESPONSIBLE);
            assertThat(c.entries()).extracting(InstallmentExpensesCommand.Entry::number).containsExactly(1, 2, 3);
            assertThat(c.entries()).extracting(e -> e.amount().toPlainString()).containsExactly("33.33", "33.33", "33.34");
        });
        assertThat(result.replayed()).isFalse();
        assertThat(result.purchase()).satisfies(p -> {
            assertThat(p.id()).isEqualTo(PURCHASE); assertThat(p.totalAmount()).isEqualTo("100.00");
            assertThat(p.installmentsSum()).isEqualTo("100.00"); assertThat(p.installmentCount()).isEqualTo(3);
            assertThat(p.lastDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(p.categoryName()).isEqualTo("Casa"); assertThat(p.responsibleDisplayName()).isEqualTo("Beto");
            assertThat(p.createdByDisplayName()).isEqualTo("Ana"); assertThat(p.createdAt()).isEqualTo(NOW);
            assertThat(p.installments()).extracting(InstallmentView::number).containsExactly(1, 2, 3);
            assertThat(p.installments()).allSatisfy(i -> {
                assertThat(i.expenseId()).isNotNull(); assertThat(i.status()).isEqualTo(ExpenseStatus.PENDING);
                assertThat(i.count()).isEqualTo(3);
            });
        });
    }

    @Test
    void theSameDataGivesTheSameFingerprintAndDifferentDataAnotherOne() {
        service.create(EMAIL, command("100.0", 3, LocalDate.of(2026, 10, 31), KEY));
        service.create(EMAIL, new InstallmentPurchaseCommand(" Sofá ", "100", 3, LocalDate.of(2026, 10, 31), CATEGORY,
                RESPONSIBLE, KEY));
        service.create(EMAIL, command("100.01", 3, LocalDate.of(2026, 10, 31), KEY));
        service.create(EMAIL, command("100", 4, LocalDate.of(2026, 10, 31), KEY));
        service.create(EMAIL, command("100", 3, LocalDate.of(2026, 11, 30), KEY));
        service.create(EMAIL, new InstallmentPurchaseCommand("Sofá", "100", 3, LocalDate.of(2026, 10, 31), null,
                RESPONSIBLE, KEY));
        service.create(EMAIL, new InstallmentPurchaseCommand("Sofá", "100", 3, LocalDate.of(2026, 10, 31), CATEGORY,
                null, KEY));
        service.create(EMAIL, new InstallmentPurchaseCommand("Sofá 2", "100", 3, LocalDate.of(2026, 10, 31), CATEGORY,
                RESPONSIBLE, KEY));
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(repository, times(8)).claim(eq(SPACE), eq(ACTOR), eq(KEY), hashes.capture(), eq(NOW));
        var values = hashes.getAllValues();
        assertThat(values.get(0)).isEqualTo(values.get(1));
        assertThat(values.subList(1, 8)).doesNotHaveDuplicates();
    }

    @Test
    void aReplayReturnsTheFirstPurchaseWithoutRevalidatingOrWriting() {
        when(repository.claim(any(), any(), any(), anyString(), any())).thenReturn(new PurchaseClaim(true, PURCHASE));
        doThrow(new CategoryConflictException("arquivada")).when(categories).requireSelectable(any(), any());

        var result = service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY));

        assertThat(result.replayed()).isTrue();
        assertThat(result.purchase().id()).isEqualTo(PURCHASE);
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).complete(any(), any(), any(), any(), any());
        verify(expenses, never()).create(any());
    }

    @Test
    void rejectsInvalidDataBeforeClaimingTheKey() {
        assertThatThrownBy(() -> service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), null)))
                .isInstanceOf(InstallmentValidationException.class).extracting("field").isEqualTo("Idempotency-Key");
        assertThatThrownBy(() -> service.create(EMAIL, command("abc", 3, LocalDate.of(2026, 10, 31), KEY)))
                .hasMessageContaining("decimal válido").extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> service.create(EMAIL, command(" ", 3, LocalDate.of(2026, 10, 31), KEY)))
                .hasMessageContaining("valor total").extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> service.create(EMAIL, command(null, 3, LocalDate.of(2026, 10, 31), KEY)))
                .extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> service.create(EMAIL, command("0.02", 3, LocalDate.of(2026, 10, 31), KEY)))
                .extracting("field").isEqualTo("totalAmount");
        assertThatThrownBy(() -> service.preview(EMAIL, command("100", 361, LocalDate.of(2026, 10, 31), null)))
                .extracting("field").isEqualTo("installmentCount");
        assertThatThrownBy(() -> service.create(EMAIL, null)).isInstanceOf(NullPointerException.class);
        verify(repository, never()).claim(any(), any(), any(), anyString(), any());
        verifyNoInteractions(expenses);
    }

    @Test
    void ineligibleResponsibleOrCategoryStopsTheCreation() {
        doThrow(new AuthenticatedUserContextNotFoundException()).when(members).requireActiveParticipants(SPACE, ACTOR, RESPONSIBLE);
        assertThatThrownBy(() -> service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY)))
                .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("responsável")
                .extracting("field").isEqualTo("responsibleUserId");
        assertThatThrownBy(() -> service.preview(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), null)))
                .extracting("field").isEqualTo("responsibleUserId");

        doNothing().when(members).requireActiveParticipants(SPACE, ACTOR, RESPONSIBLE);
        doThrow(new CategoryConflictException("arquivada")).when(categories).requireSelectable(SPACE, CATEGORY);
        assertThatThrownBy(() -> service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY)))
                .isInstanceOf(CategoryConflictException.class);
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(expenses);
    }

    @Test
    void anInactiveAuthorIsRejectedBeforeAnything() {
        doThrow(new AuthenticatedUserContextNotFoundException()).when(members).requireActiveParticipants(SPACE, ACTOR, null);
        assertThatThrownBy(() -> service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        verifyNoInteractions(repository, expenses);
    }

    @Test
    void withoutOptionalReferencesOnlyTheAuthorIsChecked() {
        service.create(EMAIL, new InstallmentPurchaseCommand("Sofá", "100", 3, LocalDate.of(2026, 10, 31), null, null, KEY));
        verify(members, times(1)).requireActiveParticipants(any(), any(), any());
        verify(categories).requireSelectable(SPACE, null);
    }

    @Test
    void failsWhenTheExpensesModuleDoesNotCreateEveryInstallment() {
        doReturn(List.of()).when(expenses).create(any());
        assertThatThrownBy(() -> service.create(EMAIL, command("100", 3, LocalDate.of(2026, 10, 31), KEY)))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void detailShowsProgressFromTheInstallmentSituationsInTheActorsTimeZone() {
        // 2026-09-28T02:00Z is still 2026-09-27 in São Paulo: an installment due on the 27th is not overdue yet.
        service = new InstallmentPurchaseService(repository, expenses, email -> new AuthenticatedUserContext(ACTOR, "Ana",
                email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR", "America/Sao_Paulo"), categories, members,
                Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneOffset.UTC), () -> PURCHASE);
        created.addAll(List.of(
                paid(snapshot(PURCHASE, 1, 5, "20.00", LocalDate.of(2026, 8, 27), ExpenseStatus.PAID), "19.50"),
                snapshot(PURCHASE, 2, 5, "20.00", LocalDate.of(2026, 9, 26), ExpenseStatus.PENDING),
                snapshot(PURCHASE, 3, 5, "20.00", LocalDate.of(2026, 9, 27), ExpenseStatus.PENDING),
                snapshot(PURCHASE, 4, 5, "20.00", LocalDate.of(2026, 10, 27), ExpenseStatus.CANCELLED),
                snapshot(PURCHASE, 5, 5, "20.01", LocalDate.of(2026, 11, 27), ExpenseStatus.PENDING)));

        var view = service.get(EMAIL, PURCHASE);

        assertThat(view.progress()).isEqualTo(new InstallmentProgress(5, 1, 3, 1, 1, "20.00", "60.01", "20.00", "20.00",
                LocalDate.of(2026, 9, 26)));
        assertThat(view.installmentsSum()).isEqualTo("100.01");
        assertThat(view.lastDueDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(view.installments()).extracting(InstallmentView::overdue).containsExactly(false, true, false, false, false);
        assertThat(view.installments().getFirst()).satisfies(i -> {
            assertThat(i.paymentDate()).isEqualTo(LocalDate.of(2026, 8, 27)); assertThat(i.paidAmount()).isEqualTo("19.50");
            assertThat(i.version()).isEqualTo(3L); assertThat(i.description()).isEqualTo("Sofá 1");
            assertThat(i.categoryName()).isEqualTo("Casa"); assertThat(i.responsibleDisplayName()).isEqualTo("Beto");
            assertThat(i.categoryId()).isEqualTo(CATEGORY); assertThat(i.responsibleUserId()).isEqualTo(RESPONSIBLE);
        });
        assertThat(view.installments().get(1).paidAmount()).isNull();
        verifyNoInteractions(members, categories);
        verify(repository, never()).claim(any(), any(), any(), anyString(), any());
    }

    @Test
    void progressWithoutPendingInstallmentsHasNoNextDueDateAndZeroAmounts() {
        var progress = InstallmentProgress.of(List.of(
                snapshot(PURCHASE, 1, 2, "10.00", LocalDate.of(2026, 1, 1), ExpenseStatus.PAID),
                snapshot(PURCHASE, 2, 2, "10.00", LocalDate.of(2026, 2, 1), ExpenseStatus.CANCELLED)),
                LocalDate.of(2026, 9, 28));
        assertThat(progress).isEqualTo(new InstallmentProgress(2, 1, 0, 0, 1, "10.00", "0.00", "0.00", "10.00", null));
        assertThat(InstallmentProgress.of(List.of(), LocalDate.of(2026, 9, 28)).nextDueDate()).isNull();
    }

    @Test
    void theNextDueDateIsTheEarliestPendingOneWhateverTheOrder() {
        var progress = InstallmentProgress.of(List.of(
                snapshot(PURCHASE, 3, 3, "10.00", LocalDate.of(2026, 12, 1), ExpenseStatus.PENDING),
                snapshot(PURCHASE, 2, 3, "10.00", LocalDate.of(2026, 11, 1), ExpenseStatus.PENDING),
                snapshot(PURCHASE, 1, 3, "10.00", LocalDate.of(2026, 10, 1), ExpenseStatus.PAID)),
                LocalDate.of(2026, 9, 28));
        assertThat(progress.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(progress.overdueCount()).isZero();
    }

    @Test
    void anUnknownOrForeignPurchaseIsNotFound() {
        var other = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(repository.find(SPACE, other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(EMAIL, other)).isInstanceOf(InstallmentPurchaseNotFoundException.class)
                .hasMessageContaining("não encontrada");
        verifyNoInteractions(expenses);
    }

    @Test
    void listPagesPurchasesAndGroupsTheirInstallmentsInOneQuery() {
        var second = UUID.fromString("00000000-0000-0000-0000-000000000010");
        when(repository.list(SPACE, 40, 20)).thenReturn(List.of(stored(PURCHASE), stored(second)));
        when(repository.count(SPACE)).thenReturn(42L);
        when(expenses.findByPurchases(SPACE, List.of(PURCHASE, second))).thenReturn(List.of(
                snapshot(PURCHASE, 1, 2, "50.00", LocalDate.of(2026, 9, 1), ExpenseStatus.PENDING),
                snapshot(PURCHASE, 2, 2, "50.00", LocalDate.of(2026, 10, 1), ExpenseStatus.PENDING)));

        var page = service.list(EMAIL, 2, 20);

        assertThat(page.page()).isEqualTo(2); assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalItems()).isEqualTo(42);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst()).satisfies(p -> {
            assertThat(p.id()).isEqualTo(PURCHASE); assertThat(p.totalAmount()).isEqualTo("100.00");
            assertThat(p.installmentCount()).isEqualTo(3); assertThat(p.firstDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(p.lastDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(p.categoryName()).isEqualTo("Casa"); assertThat(p.responsibleDisplayName()).isEqualTo("Beto");
            assertThat(p.createdAt()).isEqualTo(NOW); assertThat(p.description()).isEqualTo("Sofá");
            assertThat(p.progress().pendingCount()).isEqualTo(2); assertThat(p.progress().overdueCount()).isEqualTo(1);
            assertThat(p.progress().pendingAmount()).isEqualTo("100.00");
        });
        assertThat(page.items().get(1).lastDueDate()).isNull();
        assertThat(page.items().get(1).progress().installmentCount()).isZero();
    }

    @Test
    void listRejectsInvalidPagingBeforeReadingAnything() {
        assertThatThrownBy(() -> service.list(EMAIL, -1, 20)).extracting("field").isEqualTo("page");
        assertThatThrownBy(() -> service.list(EMAIL, 0, 0)).extracting("field").isEqualTo("size");
        assertThatThrownBy(() -> service.list(EMAIL, 0, 101)).extracting("field").isEqualTo("size");
        verifyNoInteractions(repository, expenses);
        when(repository.list(SPACE, 0, 100)).thenReturn(List.of());
        assertThat(service.list(EMAIL, 0, 100).items()).isEmpty();
        when(repository.list(SPACE, 0, 1)).thenReturn(List.of());
        assertThat(service.list(EMAIL, 0, 1).size()).isEqualTo(1);
    }

    private static StoredInstallmentPurchase stored(UUID id) {
        return new StoredInstallmentPurchase(id, SPACE, "Sofá", new BigDecimal("100.00"), 3, LocalDate.of(2026, 10, 31),
                CATEGORY, "Casa", RESPONSIBLE, "Beto", ACTOR, "Ana", NOW);
    }

    private static InstallmentExpenseSnapshot snapshot(UUID purchase, int number, int count, String amount,
            LocalDate due, ExpenseStatus status) {
        return new InstallmentExpenseSnapshot(UUID.randomUUID(), purchase, number, count, new BigDecimal(amount), due,
                status, 3, "Sofá " + number, CATEGORY, "Casa", RESPONSIBLE, "Beto", null, null);
    }

    private static InstallmentExpenseSnapshot paid(InstallmentExpenseSnapshot s, String paidAmount) {
        return new InstallmentExpenseSnapshot(s.expenseId(), s.purchaseId(), s.number(), s.count(), s.amount(),
                s.dueDate(), s.status(), s.version(), s.description(), s.categoryId(), s.categoryName(),
                s.responsibleUserId(), s.responsibleDisplayName(), s.dueDate(), new BigDecimal(paidAmount));
    }

    private static InstallmentPurchaseCommand command(String total, int count, LocalDate first, UUID key) {
        return new InstallmentPurchaseCommand("Sofá", total, count, first, CATEGORY, RESPONSIBLE, key);
    }
}
