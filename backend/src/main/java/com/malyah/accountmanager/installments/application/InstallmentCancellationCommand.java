package com.malyah.accountmanager.installments.application;

import java.util.List;
import java.util.UUID;

/**
 * H05.3: cancels the selected pending installments. An optional replacement creates, in the same transaction, a new
 * purchase for the remainder (RF-PAR-07: correcting total or quantity); its own idempotency key is ignored.
 */
public record InstallmentCancellationCommand(UUID purchaseId, List<Integer> installmentNumbers, String reason,
        InstallmentPurchaseCommand replacement, String impactToken, UUID idempotencyKey) {
    public InstallmentCancellationCommand {
        installmentNumbers = installmentNumbers == null ? List.of() : List.copyOf(installmentNumbers);
    }
}
