package com.malyah.accountmanager.foundation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DomainPreconditionsTest {

    private final DomainPreconditions preconditions = new DomainPreconditions();

    @Test
    void returnsValidValue() {
        assertThat(preconditions.requireNonBlank("probe", "required")).isEqualTo("probe");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> preconditions.requireNonBlank(null, "required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("required");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> preconditions.requireNonBlank("  ", "required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("required");
    }
}
