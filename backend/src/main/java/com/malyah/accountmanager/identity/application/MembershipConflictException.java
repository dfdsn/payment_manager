package com.malyah.accountmanager.identity.application;

public final class MembershipConflictException extends RuntimeException {
    public MembershipConflictException(String message) {
        super(message);
    }
}
