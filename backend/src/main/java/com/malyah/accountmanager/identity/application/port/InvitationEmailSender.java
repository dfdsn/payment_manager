package com.malyah.accountmanager.identity.application.port;

import com.malyah.accountmanager.identity.application.PendingInvitationEmail;

public interface InvitationEmailSender {

    void send(PendingInvitationEmail email);
}
