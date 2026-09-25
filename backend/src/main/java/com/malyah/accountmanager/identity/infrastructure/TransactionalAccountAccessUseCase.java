package com.malyah.accountmanager.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.AccountAccessService;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.PendingAccessEmail;
import com.malyah.accountmanager.identity.application.port.AccessEmailSender;

final class TransactionalAccountAccessUseCase implements AccountAccessUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalAccountAccessUseCase.class);

    private final AccountAccessService delegate;
    private final AccessEmailSender emailSender;
    private final TransactionTemplate transactionTemplate;

    TransactionalAccountAccessUseCase(
            AccountAccessService delegate,
            AccessEmailSender emailSender,
            TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.emailSender = emailSender;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void requestEmailConfirmation(String email) {
        var pending = transactionTemplate.execute(status -> delegate.prepareEmailConfirmation(email));
        pending.ifPresent(this::sendWithoutAccountDisclosure);
    }

    @Override
    public void confirmEmail(String token) {
        transactionTemplate.executeWithoutResult(status -> delegate.confirmEmail(token));
    }

    @Override
    public void requestPasswordReset(String email) {
        var pending = transactionTemplate.execute(status -> delegate.preparePasswordReset(email));
        pending.ifPresent(this::sendWithoutAccountDisclosure);
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        transactionTemplate.executeWithoutResult(status -> delegate.resetPassword(token, newPassword));
    }

    private void sendWithoutAccountDisclosure(PendingAccessEmail pending) {
        try {
            emailSender.send(pending);
        } catch (RuntimeException exception) {
            LOGGER.warn("Falha ao entregar email transacional de acesso; o token permanece elegível para reenvio explícito.");
        }
    }
}
