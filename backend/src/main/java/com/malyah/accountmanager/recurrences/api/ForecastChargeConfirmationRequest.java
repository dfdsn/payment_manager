package com.malyah.accountmanager.recurrences.api;

import jakarta.validation.constraints.NotBlank;

record ForecastChargeConfirmationRequest(
        @NotBlank(message="Informe o valor confirmado da cobrança.") String confirmedAmount) { }
