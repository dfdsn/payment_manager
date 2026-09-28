package com.malyah.accountmanager.installments.application;

public interface InstallmentPurchaseUseCase {
    InstallmentPreviewView preview(String actorEmail, InstallmentPurchaseCommand command);

    InstallmentPurchaseCreationResult create(String actorEmail, InstallmentPurchaseCommand command);
}
