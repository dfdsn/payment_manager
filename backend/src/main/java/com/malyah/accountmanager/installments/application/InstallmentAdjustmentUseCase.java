package com.malyah.accountmanager.installments.application;

public interface InstallmentAdjustmentUseCase {
    InstallmentImpactView previewChange(String actorEmail, InstallmentChangeCommand command);

    InstallmentChangeResult applyChange(String actorEmail, InstallmentChangeCommand command);

    InstallmentImpactView previewCancellation(String actorEmail, InstallmentCancellationCommand command);

    InstallmentChangeResult applyCancellation(String actorEmail, InstallmentCancellationCommand command);
}
