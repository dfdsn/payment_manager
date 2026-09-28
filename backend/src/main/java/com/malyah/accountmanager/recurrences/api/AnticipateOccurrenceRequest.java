package com.malyah.accountmanager.recurrences.api;

import jakarta.validation.constraints.AssertTrue;

record AnticipateOccurrenceRequest(
        @AssertTrue(message="Confirme a antecipação do lançamento.") boolean confirmed) { }
