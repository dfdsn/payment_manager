package com.malyah.accountmanager.notifications.application;

/** Not a notification of this member in the active space (someone else's is reported the same way). */
public final class MemberNotificationNotFoundException extends RuntimeException {
    public MemberNotificationNotFoundException() {
        super("Aviso não encontrado.");
    }
}
