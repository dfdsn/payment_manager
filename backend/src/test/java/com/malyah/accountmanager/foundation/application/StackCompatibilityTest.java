package com.malyah.accountmanager.foundation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StackCompatibilityTest {

    @Test
    void delegatesInvariantAndNormalizesValue() {
        assertThat(new StackCompatibility().normalizedProbeName("  stack-ok  ")).isEqualTo("stack-ok");
    }
}

