package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.NotBlank;

record AccessTokenRequest(@NotBlank(message = "Informe o token.") String token) {
}
