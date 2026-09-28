package com.malyah.accountmanager.installments.api;

import java.util.List;
import jakarta.validation.Valid;

record InstallmentCancellationRequest(List<Integer> installmentNumbers, String reason,
        @Valid InstallmentPurchaseRequest replacement, String impactToken) { }
