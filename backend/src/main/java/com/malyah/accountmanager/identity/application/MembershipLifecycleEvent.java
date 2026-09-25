package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.malyah.accountmanager.identity.domain.SpaceRole;

public record MembershipLifecycleEvent(
        UUID id,
        UUID spaceId,
        UUID actorUserId,
        UUID subjectUserId,
        Type type,
        SpaceRole actorPreviousRole,
        SpaceRole actorNewRole,
        SpaceRole subjectPreviousRole,
        SpaceRole subjectNewRole,
        Instant occurredAt) {

    public enum Type {
        MEMBER_LEFT, MEMBER_REMOVED, ADMINISTRATION_TRANSFERRED
    }
}
