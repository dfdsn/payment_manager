package com.malyah.accountmanager.identity.application.port;

public interface PasswordHasher {

    String hash(char[] password);
}
