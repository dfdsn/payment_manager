package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.NotBlank;

record PasswordResetRequest(
        @NotBlank(message = "Informe o token.") String token,
        @NotBlank(message = "Informe a nova senha.") String newPassword) {
}
