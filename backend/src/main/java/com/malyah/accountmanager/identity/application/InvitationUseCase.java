package com.malyah.accountmanager.identity.application;

import java.util.Optional;

public interface InvitationUseCase {

    Optional<InvitationStatus> current(String actorEmail);

    void invite(String actorEmail, String invitedEmail);

    void resend(String actorEmail);

    void revoke(String actorEmail);

    InvitationPreview preview(String rawToken, String authenticatedEmail);

    void accept(InvitationAcceptanceCommand command);
}
