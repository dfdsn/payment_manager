package com.malyah.accountmanager.identity.domain;

import java.text.Normalizer;

public record SpaceName(String value) {

    public SpaceName {
        if (value == null || value.isBlank()) {
            throw new IdentityValidationException("spaceName", "Informe o nome do espaço.");
        }
        value = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        if (value.length() < 2 || value.length() > 100) {
            throw new IdentityValidationException(
                    "spaceName", "O nome do espaço deve ter entre 2 e 100 caracteres.");
        }
    }
}
