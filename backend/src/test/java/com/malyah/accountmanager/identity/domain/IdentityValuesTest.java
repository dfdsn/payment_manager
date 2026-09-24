package com.malyah.accountmanager.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityValuesTest {

    @Test
    void normalizesIdentityValues() {
        assertThat(new AdministratorName("  Diego   Silva ").value()).isEqualTo("Diego Silva");
        assertThat(new SpaceName("  Minha   família ").value()).isEqualTo("Minha família");
        assertThat(new EmailAddress("  Diego@Example.COM ").value()).isEqualTo("diego@example.com");
    }

    @Test
    void rejectsMissingAndMalformedValues() {
        assertField(() -> new AdministratorName(" "), "administratorName");
        assertField(() -> new AdministratorName("A"), "administratorName");
        assertField(() -> new AdministratorName("a".repeat(101)), "administratorName");
        assertField(() -> new SpaceName(null), "spaceName");
        assertField(() -> new SpaceName("x"), "spaceName");
        assertField(() -> new SpaceName("x".repeat(101)), "spaceName");
        assertField(() -> new EmailAddress(null), "email");
        assertField(() -> new EmailAddress("invalido"), "email");
        assertField(() -> new EmailAddress("a".repeat(250) + "@x.com"), "email");
    }

    @Test
    void enforcesPasswordPolicyWithoutRequiringSymbols() {
        var policy = new PasswordPolicy();
        policy.validate("frase longa 2026".toCharArray());

        assertField(() -> policy.validate(null), "password");
        assertField(() -> policy.validate("curta1".toCharArray()), "password");
        assertField(() -> policy.validate("somenteletras".toCharArray()), "password");
        assertField(() -> policy.validate("123456789012".toCharArray()), "password");
        assertField(() -> policy.validate(("á".repeat(36) + "a1").toCharArray()), "password");
    }

    private void assertField(Runnable operation, String field) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(IdentityValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo(field));
    }
}
