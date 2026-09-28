package com.malyah.accountmanager.installments.domain;

public final class InstallmentValidationException extends RuntimeException {
    private final String field;

    public InstallmentValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
