package com.malyah.accountmanager.foundation.domain;

/**
 * Base técnica para invariantes de domínio. Regras funcionais serão adicionadas
 * apenas nas respectivas histórias.
 */
public final class DomainPreconditions {

    public String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
