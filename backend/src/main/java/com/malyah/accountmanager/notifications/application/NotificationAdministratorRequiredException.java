package com.malyah.accountmanager.notifications.application;

public final class NotificationAdministratorRequiredException extends RuntimeException {
    public NotificationAdministratorRequiredException() {
        super("Somente o administrador altera horários e o WhatsApp.");
    }
}
