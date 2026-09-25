package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record InvitationAcceptanceRequest(
        @NotBlank(message = "Informe o token do convite.")
        @Size(max = 512, message = "O token do convite é inválido.")
        String token,
        @Size(max = 100, message = "O nome deve ter no máximo 100 caracteres.")
        String displayName,
        @Size(max = 512, message = "A senha informada é inválida.")
        String password) {
}
