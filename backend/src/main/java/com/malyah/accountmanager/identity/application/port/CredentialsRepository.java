package com.malyah.accountmanager.identity.application.port;

import java.util.Optional;

import com.malyah.accountmanager.identity.application.LoginCredentials;

public interface CredentialsRepository {

    Optional<LoginCredentials> findActiveByNormalizedEmail(String normalizedEmail);
}
