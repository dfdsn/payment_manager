package com.malyah.accountmanager.installments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstallmentChangeTest {
    private static final UUID CAT = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID NEW_CAT = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID RESP = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    // 1 paid, 2 pending, 3 cancelled, 4 and 5 pending; monthly on the 31st (short months on their last day).
    private static final List<InstallmentState> STATES = List.of(
            state(1, false, LocalDate.of(2027, 1, 31)), state(2, true, LocalDate.of(2027, 2, 28)),
            state(3, false, LocalDate.of(2027, 3, 31)), state(4, true, LocalDate.of(2027, 4, 30)),
            state(5, true, LocalDate.of(2027, 5, 31)));

    @Test
    void metadataChangeReachesTheChosenAndFollowingPendingInstallmentsOnly() {
        var updates = new InstallmentChange(2, InstallmentChangeScope.THIS_AND_FOLLOWING,
                Set.of("description", "categoryId", "responsibleUserId"), "  Sofá novo ", NEW_CAT, null, null)
                .plan(STATES);

        assertThat(updates).extracting(PlannedInstallmentUpdate::number).containsExactly(2, 4, 5);
        assertThat(updates).allSatisfy(u -> {
            assertThat(u.description()).isEqualTo("Sofá novo");
            assertThat(u.categoryId()).isEqualTo(NEW_CAT);
            assertThat(u.responsibleUserId()).isNull();
            assertThat(u.changedFields()).containsExactly("description", "categoryId", "responsibleUserId");
        });
        assertThat(updates.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void scopeThisChangesOnlyTheChosenInstallment() {
        var updates = new InstallmentChange(4, InstallmentChangeScope.THIS, Set.of("description"), "Só a quarta", null,
                null, null).plan(STATES);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.number()).isEqualTo(4);
            assertThat(u.categoryId()).isEqualTo(CAT);
            assertThat(u.responsibleUserId()).isEqualTo(RESP);
            assertThat(u.dueDate()).isEqualTo(LocalDate.of(2027, 4, 30));
            assertThat(u.changedFields()).containsExactly("description");
        });
    }

    @Test
    void aNewDueDateRecalculatesTheFollowingPendingMonthByMonthKeepingTheDay() {
        var updates = new InstallmentChange(2, InstallmentChangeScope.THIS_AND_FOLLOWING, Set.of("dueDate"), null, null,
                null, LocalDate.of(2027, 3, 30)).plan(STATES);
        // The paid 1 and cancelled 3 stay; 4 is two months after 2, and 5 three months after, both on the 30th.
        assertThat(updates).extracting(PlannedInstallmentUpdate::number, PlannedInstallmentUpdate::dueDate)
                .containsExactly(tuple(2, LocalDate.of(2027, 3, 30)), tuple(4, LocalDate.of(2027, 5, 30)),
                        tuple(5, LocalDate.of(2027, 6, 30)));
        assertThat(updates).allSatisfy(u -> {
            assertThat(u.changedFields()).containsExactly("dueDate");
            assertThat(u.description()).isEqualTo("Sofá");
        });
    }

    @Test
    void shortMonthsUseTheirLastDayAndLaterMonthsReturnToTheDay() {
        var states = List.of(state(1, true, LocalDate.of(2027, 1, 10)), state(2, true, LocalDate.of(2027, 2, 10)),
                state(3, true, LocalDate.of(2027, 3, 10)));
        var updates = new InstallmentChange(1, InstallmentChangeScope.THIS_AND_FOLLOWING, Set.of("dueDate"), null, null,
                null, LocalDate.of(2027, 1, 31)).plan(states);
        assertThat(updates).extracting(PlannedInstallmentUpdate::dueDate).containsExactly(LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31));
    }

    @Test
    void aDueDateWithScopeThisMovesOnlyTheChosenInstallment() {
        var updates = new InstallmentChange(4, InstallmentChangeScope.THIS, Set.of("dueDate"), null, null, null,
                LocalDate.of(2027, 5, 2)).plan(STATES);
        assertThat(updates).extracting(PlannedInstallmentUpdate::number, PlannedInstallmentUpdate::dueDate)
                .containsExactly(tuple(4, LocalDate.of(2027, 5, 2)));
    }

    @Test
    void installmentsThatAlreadyHaveTheValuesAreSkippedAndNothingToChangeIsRejected() {
        var states = List.of(state(1, true, LocalDate.of(2027, 1, 31)),
                new InstallmentState(2, true, LocalDate.of(2027, 2, 28), "Outra", CAT, RESP));
        var updates = new InstallmentChange(1, InstallmentChangeScope.THIS_AND_FOLLOWING, Set.of("description"), "Outra",
                null, null, null).plan(states);
        assertThat(updates).extracting(PlannedInstallmentUpdate::number).containsExactly(1);

        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS_AND_FOLLOWING,
                Set.of("categoryId", "responsibleUserId"), null, CAT, RESP, null).plan(states))
                .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("Nenhuma parcela")
                .extracting("field").isEqualTo("changedFields");
    }

    @Test
    void clearingCategoryAndResponsibleIsAChange() {
        var updates = new InstallmentChange(5, InstallmentChangeScope.THIS, Set.of("categoryId", "responsibleUserId"),
                "ignorada", null, null, LocalDate.of(2030, 1, 1)).plan(STATES);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.categoryId()).isNull(); assertThat(u.responsibleUserId()).isNull();
            assertThat(u.description()).isEqualTo("Sofá"); assertThat(u.dueDate()).isEqualTo(LocalDate.of(2027, 5, 31));
            assertThat(u.changedFields()).containsExactly("categoryId", "responsibleUserId");
        });
    }

    @Test
    void theChosenInstallmentMustExistAndBePending() {
        var change = new InstallmentChange(3, InstallmentChangeScope.THIS, Set.of("description"), "X", null, null, null);
        assertThatThrownBy(() -> change.plan(STATES)).isInstanceOf(InstallmentStateConflictException.class)
                .hasMessageContaining("parcela 3 não está pendente");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("description"), "X", null,
                null, null).plan(STATES)).isInstanceOf(InstallmentStateConflictException.class);
        assertThatThrownBy(() -> new InstallmentChange(9, InstallmentChangeScope.THIS, Set.of("description"), "X", null,
                null, null).plan(STATES)).isInstanceOf(InstallmentValidationException.class)
                .extracting("field").isEqualTo("fromNumber");
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> new InstallmentChange(1, null, Set.of("description"), "X", null, null, null))
                .extracting("field").isEqualTo("scope");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of(), "X", null, null, null))
                .extracting("field").isEqualTo("changedFields");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, null, "X", null, null, null))
                .extracting("field").isEqualTo("changedFields");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("amount"), null, null, null,
                null)).hasMessageContaining("cancelar e criar nova compra").extracting("field").isEqualTo("changedFields");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("description"), "  ", null,
                null, null)).extracting("field").isEqualTo("description");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("description"), null, null,
                null, null)).extracting("field").isEqualTo("description");
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("description"),
                "x".repeat(201), null, null, null)).extracting("field").isEqualTo("description");
        assertThat(new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("description"), "x".repeat(200), null,
                null, null).description()).hasSize(200);
        assertThatThrownBy(() -> new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("dueDate"), null, null,
                null, null)).extracting("field").isEqualTo("dueDate");
    }

    @Test
    void fieldsOutsideTheRequestAreIgnored() {
        var change = new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("dueDate"), "X", CAT, RESP,
                LocalDate.of(2027, 1, 1));
        assertThat(change.description()).isNull();
        assertThat(change.categoryId()).isNull();
        assertThat(change.responsibleUserId()).isNull();
        var only = new InstallmentChange(1, InstallmentChangeScope.THIS, Set.of("categoryId"), null, CAT, RESP,
                LocalDate.of(2027, 1, 1));
        assertThat(only.dueDate()).isNull();
        assertThat(only.categoryId()).isEqualTo(CAT);
    }

    private static InstallmentState state(int number, boolean pending, LocalDate due) {
        return new InstallmentState(number, pending, due, "Sofá", CAT, RESP);
    }
}
