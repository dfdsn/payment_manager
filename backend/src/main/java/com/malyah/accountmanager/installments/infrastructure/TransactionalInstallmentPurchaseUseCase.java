package com.malyah.accountmanager.installments.infrastructure;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.installments.application.InstallmentPreviewView;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCommand;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCreationResult;
import com.malyah.accountmanager.installments.application.InstallmentPurchasePage;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseView;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;

/** Purchase, installment entries, audit and idempotency record commit together or not at all. */
final class TransactionalInstallmentPurchaseUseCase implements InstallmentPurchaseUseCase {
    private final InstallmentPurchaseService delegate;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    TransactionalInstallmentPurchaseUseCase(InstallmentPurchaseService delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
        this.readOnly = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()), transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public InstallmentPreviewView preview(String email, InstallmentPurchaseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.preview(email, command)));
    }

    @Override
    public InstallmentPurchaseCreationResult create(String email, InstallmentPurchaseCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.create(email, command)));
    }

    /** Header and installments are read in one snapshot, so the progress always matches the listed situations. */
    @Override
    public InstallmentPurchasePage list(String email, int page, int size) {
        return Objects.requireNonNull(readOnly.execute(status -> delegate.list(email, page, size)));
    }

    @Override
    public InstallmentPurchaseView get(String email, UUID purchaseId) {
        return Objects.requireNonNull(readOnly.execute(status -> delegate.get(email, purchaseId)));
    }
}
