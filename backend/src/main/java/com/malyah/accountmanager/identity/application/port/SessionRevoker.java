package com.malyah.accountmanager.identity.application.port;

public interface SessionRevoker {

    void revokeAll(String normalizedEmail);
}
