package com.malyah.accountmanager.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record InitialSetupRequest(
        @NotBlank @Size(min = 2, max = 100) String administratorName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotBlank @Size(min = 2, max = 100) String spaceName) {
}
