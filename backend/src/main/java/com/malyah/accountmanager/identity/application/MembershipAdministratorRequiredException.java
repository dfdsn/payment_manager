package com.malyah.accountmanager.identity.application;

public final class MembershipAdministratorRequiredException extends RuntimeException {
    public MembershipAdministratorRequiredException() {
        super("Somente o administrador atual pode realizar esta ação.");
    }
}
