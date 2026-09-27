package com.malyah.accountmanager.expenses.application;

public final class AttachmentException extends RuntimeException {
    private final String code;
    public AttachmentException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
