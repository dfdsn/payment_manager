package com.malyah.accountmanager.identity.domain;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    public void validate(char[] password) {
        if (password == null || password.length < 12) {
            throw new IdentityValidationException("password", "A senha deve ter pelo menos 12 caracteres.");
        }
        if (new String(password).getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentityValidationException("password", "A senha deve ocupar no máximo 72 bytes em UTF-8.");
        }
        var hasLetter = false;
        var hasDigit = false;
        for (var character : password) {
            hasLetter |= Character.isLetter(character);
            hasDigit |= Character.isDigit(character);
        }
        if (!hasLetter || !hasDigit) {
            throw new IdentityValidationException("password", "A senha deve conter ao menos uma letra e um número.");
        }
    }
}
