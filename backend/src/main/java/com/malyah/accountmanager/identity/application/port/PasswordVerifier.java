package com.malyah.accountmanager.identity.application.port;

public interface PasswordVerifier {

    boolean matches(char[] rawPassword, String passwordHash);
}
