package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record InvitationRequest(
        @NotBlank(message = "Informe o email do convidado.")
        @Email(message = "Informe um email válido.")
        @Size(max = 254, message = "O email deve ter no máximo 254 caracteres.")
        String email) {
}
