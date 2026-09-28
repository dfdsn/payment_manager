package com.malyah.accountmanager.installments.application;

import java.util.UUID;

public interface InstallmentPurchaseUseCase {
    InstallmentPreviewView preview(String actorEmail, InstallmentPurchaseCommand command);

    InstallmentPurchaseCreationResult create(String actorEmail, InstallmentPurchaseCommand command);

    InstallmentPurchasePage list(String actorEmail, int page, int size);

    InstallmentPurchaseView get(String actorEmail, UUID purchaseId);
}
