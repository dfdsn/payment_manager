package com.malyah.accountmanager.identity.infrastructure;

import java.util.Objects;

import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.InitialSetupCommand;
import com.malyah.accountmanager.identity.application.InitialSetupResult;
import com.malyah.accountmanager.identity.application.InitialSetupStatus;
import com.malyah.accountmanager.identity.application.InitialSetupUseCase;

final class TransactionalInitialSetupUseCase implements InitialSetupUseCase {

    private final InitialSetupUseCase delegate;
    private final TransactionTemplate transactionTemplate;

    TransactionalInitialSetupUseCase(InitialSetupUseCase delegate, TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public InitialSetupStatus status() {
        return delegate.status();
    }

    @Override
    public InitialSetupResult configure(InitialSetupCommand command) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> delegate.configure(command)));
    }
}
