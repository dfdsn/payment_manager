package com.malyah.accountmanager.identity.infrastructure;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.InvitationAcceptanceCommand;
import com.malyah.accountmanager.identity.application.InvitationEmailDeliveryException;
import com.malyah.accountmanager.identity.application.InvitationPreview;
import com.malyah.accountmanager.identity.application.InvitationService;
import com.malyah.accountmanager.identity.application.InvitationStatus;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.PendingInvitationEmail;
import com.malyah.accountmanager.identity.application.port.InvitationEmailSender;

final class TransactionalInvitationUseCase implements InvitationUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalInvitationUseCase.class);

    private final InvitationService delegate;
    private final InvitationEmailSender emailSender;
    private final TransactionTemplate transactionTemplate;

    TransactionalInvitationUseCase(
            InvitationService delegate,
            InvitationEmailSender emailSender,
            TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.emailSender = emailSender;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<InvitationStatus> current(String actorEmail) {
        return transactionTemplate.execute(status -> delegate.current(actorEmail));
    }

    @Override
    public void invite(String actorEmail, String invitedEmail) {
        var pending = transactionTemplate.execute(status -> delegate.prepareNew(actorEmail, invitedEmail));
        send(pending);
    }

    @Override
    public void resend(String actorEmail) {
        var pending = transactionTemplate.execute(status -> delegate.prepareResend(actorEmail));
        send(pending);
    }

    @Override
    public void revoke(String actorEmail) {
        transactionTemplate.executeWithoutResult(status -> delegate.revoke(actorEmail));
    }

    @Override
    public InvitationPreview preview(String rawToken, String authenticatedEmail) {
        return transactionTemplate.execute(status -> delegate.preview(rawToken, authenticatedEmail));
    }

    @Override
    public void accept(InvitationAcceptanceCommand command) {
        transactionTemplate.executeWithoutResult(status -> delegate.accept(command));
    }

    private void send(PendingInvitationEmail email) {
        try {
            emailSender.send(email);
        } catch (RuntimeException exception) {
            LOGGER.warn("Falha ao entregar email transacional de convite; o convite permanece disponível para reenvio.");
            throw new InvitationEmailDeliveryException();
        }
    }
}
