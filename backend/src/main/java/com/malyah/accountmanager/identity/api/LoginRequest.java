package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.NotBlank;

record LoginRequest(
        @NotBlank(message = "Informe o email.") String email,
        @NotBlank(message = "Informe a senha.") String password) {
}
