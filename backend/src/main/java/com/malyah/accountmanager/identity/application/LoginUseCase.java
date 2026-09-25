package com.malyah.accountmanager.identity.application;

public interface LoginUseCase {

    String authenticate(String email, char[] password);
}
