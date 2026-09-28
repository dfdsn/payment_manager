package com.malyah.accountmanager.installments.infrastructure;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentService;
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentUseCase;
import com.malyah.accountmanager.installments.application.InstallmentCancellationCommand;
import com.malyah.accountmanager.installments.application.InstallmentChangeCommand;
import com.malyah.accountmanager.installments.application.InstallmentChangeResult;
import com.malyah.accountmanager.installments.application.InstallmentImpactView;

/**
 * Previews run in a transaction because reference checks share-lock the category and space, like the H05.1 preview;
 * they write nothing. Audit, installment updates or cancellations, replacement purchase and idempotency record commit together.
 */
final class TransactionalInstallmentAdjustmentUseCase implements InstallmentAdjustmentUseCase {
    private final InstallmentAdjustmentService delegate;
    private final TransactionTemplate transactions;

    TransactionalInstallmentAdjustmentUseCase(InstallmentAdjustmentService delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
    }

    @Override
    public InstallmentImpactView previewChange(String email, InstallmentChangeCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.previewChange(email, command)));
    }

    @Override
    public InstallmentChangeResult applyChange(String email, InstallmentChangeCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.applyChange(email, command)));
    }

    @Override
    public InstallmentImpactView previewCancellation(String email, InstallmentCancellationCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.previewCancellation(email, command)));
    }

    @Override
    public InstallmentChangeResult applyCancellation(String email, InstallmentCancellationCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.applyCancellation(email, command)));
    }
}
