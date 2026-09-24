package com.malyah.accountmanager.identity.application.port;

import com.malyah.accountmanager.identity.application.PendingAccessEmail;

public interface AccessEmailSender {

    void send(PendingAccessEmail email);
}
