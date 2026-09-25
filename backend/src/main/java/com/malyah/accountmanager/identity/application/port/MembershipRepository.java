package com.malyah.accountmanager.identity.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.identity.application.ManagedMember;
import com.malyah.accountmanager.identity.application.MembershipActor;
import com.malyah.accountmanager.identity.application.MembershipLifecycleEvent;

public interface MembershipRepository {
    Optional<MembershipActor> findActiveActor(String normalizedEmail);
    void lockSpace(UUID spaceId);
    List<ManagedMember> findActiveMembers(UUID spaceId, UUID currentUserId);
    Optional<MembershipActor> findActiveMember(UUID spaceId, UUID userId);
    void endMembership(UUID spaceId, UUID userId, UUID endedByUserId, String reason, Instant endedAt);
    void transferAdministration(UUID spaceId, UUID currentAdministratorId, UUID newAdministratorId);
    void append(MembershipLifecycleEvent event);
}
