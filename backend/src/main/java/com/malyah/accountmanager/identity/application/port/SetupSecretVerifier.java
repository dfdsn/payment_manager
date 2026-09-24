package com.malyah.accountmanager.identity.application.port;

public interface SetupSecretVerifier {

    boolean isConfigured();

    boolean matches(String presentedSecret);
}
