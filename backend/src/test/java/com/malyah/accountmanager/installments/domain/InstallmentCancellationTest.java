package com.malyah.accountmanager.installments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstallmentCancellationTest {
    private static final List<InstallmentState> STATES = List.of(state(1, false), state(2, true), state(3, true),
            state(4, false));

    @Test
    void selectsTheRequestedPendingInstallmentsInOrderWithATrimmedReason() {
        var cancellation = new InstallmentCancellation(List.of(3, 2), "  Loja cancelou  ");
        assertThat(cancellation.reason()).isEqualTo("Loja cancelou");
        assertThat(cancellation.select(STATES)).containsExactly(2, 3);
    }

    @Test
    void paidCancelledOrUnknownInstallmentsCannotBeSelected() {
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(2, 1), "x").select(STATES))
                .isInstanceOf(InstallmentStateConflictException.class).hasMessageContaining("parcela 1");
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(4), "x").select(STATES))
                .isInstanceOf(InstallmentStateConflictException.class);
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(7), "x").select(STATES))
                .isInstanceOf(InstallmentValidationException.class).hasMessageContaining("parcela 7")
                .extracting("field").isEqualTo("installmentNumbers");
    }

    @Test
    void rejectsAnEmptyOrRepeatedSelectionAndAMissingOrLongReason() {
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(), "x")).extracting("field").isEqualTo("installmentNumbers");
        assertThatThrownBy(() -> new InstallmentCancellation(null, "x")).extracting("field").isEqualTo("installmentNumbers");
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(2, 2), "x")).hasMessageContaining("uma única vez");
        assertThatThrownBy(() -> new InstallmentCancellation(Arrays.asList(2, null), "x")).hasMessageContaining("uma única vez");
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(2), " ")).extracting("field").isEqualTo("reason");
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(2), null)).extracting("field").isEqualTo("reason");
        assertThatThrownBy(() -> new InstallmentCancellation(List.of(2), "x".repeat(2001))).extracting("field").isEqualTo("reason");
        assertThat(new InstallmentCancellation(List.of(2), "x".repeat(2000)).reason()).hasSize(2000);
    }

    private static InstallmentState state(int number, boolean pending) {
        return new InstallmentState(number, pending, LocalDate.of(2027, number, 1), "Sofá", null, null);
    }
}
