package com.malyah.accountmanager.foundation.application;

import com.malyah.accountmanager.foundation.domain.DomainPreconditions;

/** Prova executável de que application depende de domain, nunca do inverso. */
public final class StackCompatibility {

    private final DomainPreconditions preconditions = new DomainPreconditions();

    public String normalizedProbeName(String value) {
        return preconditions.requireNonBlank(value, "probeName é obrigatório").trim();
    }
}
