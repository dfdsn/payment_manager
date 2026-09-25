package com.malyah.accountmanager.identity.application;

public final class SpaceMemberLimitReachedException extends RuntimeException {

    public SpaceMemberLimitReachedException() {
        super("O espaço já possui o limite de dois membros ativos.");
    }
}
