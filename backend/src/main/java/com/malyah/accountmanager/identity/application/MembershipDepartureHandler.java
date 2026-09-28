package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Public cross-module contract invoked inside the membership transaction. */
public interface MembershipDepartureHandler {
    void beforeMembershipEnds(UUID spaceId, UUID departingUserId, UUID actorUserId, Instant occurredAt);
}
