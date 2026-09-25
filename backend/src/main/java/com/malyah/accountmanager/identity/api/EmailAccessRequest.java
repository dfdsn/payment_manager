package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

record EmailAccessRequest(
        @NotBlank(message = "Informe o email.")
        @Email(message = "Informe um email válido.")
        String email) {
}
