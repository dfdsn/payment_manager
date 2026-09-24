package com.malyah.accountmanager.identity.domain;

import java.text.Normalizer;

public record AdministratorName(String value) {

    public AdministratorName {
        value = normalize(value, "administratorName", "Informe o nome do administrador.");
        if (value.length() < 2 || value.length() > 100) {
            throw new IdentityValidationException(
                    "administratorName", "O nome do administrador deve ter entre 2 e 100 caracteres.");
        }
    }

    private static String normalize(String raw, String field, String requiredMessage) {
        if (raw == null || raw.isBlank()) {
            throw new IdentityValidationException(field, requiredMessage);
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }
}
