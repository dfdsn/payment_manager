package com.malyah.accountmanager.identity.domain;

public record MemberName(String value) {

    public MemberName(String value) {
        if (value == null || value.trim().length() < 2 || value.trim().length() > 100) {
            throw new IdentityValidationException("displayName", "Informe um nome entre 2 e 100 caracteres.");
        }
        this.value = value.trim();
    }
}
