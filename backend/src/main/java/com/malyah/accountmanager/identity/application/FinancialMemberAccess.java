package com.malyah.accountmanager.identity.application;

import java.util.UUID;

/** Must run inside the caller's transaction; locks serialize financial writes with member departure. */
public interface FinancialMemberAccess {
    void requireActiveParticipants(UUID spaceId, UUID actorId, UUID payerId);
}
