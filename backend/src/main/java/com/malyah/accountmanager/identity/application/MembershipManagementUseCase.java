package com.malyah.accountmanager.identity.application;

import java.util.List;
import java.util.UUID;

public interface MembershipManagementUseCase {
    List<ManagedMember> members(String actorEmail);
    void remove(String actorEmail, UUID memberUserId);
    void leave(String actorEmail);
    void transferAdministration(String actorEmail, UUID newAdministratorUserId);
}
