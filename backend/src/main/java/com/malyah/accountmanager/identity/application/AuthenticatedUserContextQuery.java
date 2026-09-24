package com.malyah.accountmanager.identity.application;

public interface AuthenticatedUserContextQuery {

    AuthenticatedUserContext findByEmail(String email);
}
