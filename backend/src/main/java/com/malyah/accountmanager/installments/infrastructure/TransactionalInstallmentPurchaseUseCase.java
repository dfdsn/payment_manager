package com.malyah.accountmanager.installments.infrastructure;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.installments.application.InstallmentPreviewView;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCommand;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCreationResult;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;

/** Purchase, installment entries, audit and idempotency record commit together or not at all. */
final class TransactionalInstallmentPurchaseUseCase implements InstallmentPurchaseUseCase {
    private final InstallmentPurchaseService delegate;
    private final TransactionTemplate transactions;

    TransactionalInstallmentPurchaseUseCase(InstallmentPurchaseService delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
    }

    @Override
    public InstallmentPreviewView preview(String email, InstallmentPurchaseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.preview(email, command)));
    }

    @Override
    public InstallmentPurchaseCreationResult create(String email, InstallmentPurchaseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.create(email, command)));
    }
}
