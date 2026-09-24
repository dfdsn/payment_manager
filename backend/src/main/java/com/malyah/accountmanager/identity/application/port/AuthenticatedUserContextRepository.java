package com.malyah.accountmanager.identity.application.port;

import java.util.Optional;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;

public interface AuthenticatedUserContextRepository {

    Optional<AuthenticatedUserContext> findActiveByEmail(String normalizedEmail);
}
