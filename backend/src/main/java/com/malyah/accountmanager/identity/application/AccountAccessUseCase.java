package com.malyah.accountmanager.identity.application;

public interface AccountAccessUseCase {

    void requestEmailConfirmation(String email);

    void confirmEmail(String token);

    void requestPasswordReset(String email);

    void resetPassword(String token, String newPassword);
}
