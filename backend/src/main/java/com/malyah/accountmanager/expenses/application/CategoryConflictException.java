package com.malyah.accountmanager.expenses.application;

public final class CategoryConflictException extends RuntimeException {
    public CategoryConflictException(String message) { super(message); }
}
