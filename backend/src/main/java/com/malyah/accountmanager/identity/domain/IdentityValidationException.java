package com.malyah.accountmanager.identity.domain;

public final class IdentityValidationException extends RuntimeException {

    private final String field;

    public IdentityValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
