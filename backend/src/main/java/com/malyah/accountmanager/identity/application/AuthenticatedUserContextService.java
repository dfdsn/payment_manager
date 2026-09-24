package com.malyah.accountmanager.identity.application;

import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.EmailAddress;

public final class AuthenticatedUserContextService implements AuthenticatedUserContextQuery {

    private final AuthenticatedUserContextRepository repository;

    public AuthenticatedUserContextService(AuthenticatedUserContextRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuthenticatedUserContext findByEmail(String email) {
        return repository.findActiveByEmail(new EmailAddress(email).value())
                .orElseThrow(AuthenticatedUserContextNotFoundException::new);
    }
}
