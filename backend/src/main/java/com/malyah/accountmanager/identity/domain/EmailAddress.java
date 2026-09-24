package com.malyah.accountmanager.identity.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public record EmailAddress(String value) {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new IdentityValidationException("email", "Informe o email do administrador.");
        }
        value = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !SIMPLE_EMAIL.matcher(value).matches()) {
            throw new IdentityValidationException("email", "Informe um email válido com até 254 caracteres.");
        }
    }
}
