package com.malyah.accountmanager.identity.application;

import java.util.UUID;

import com.malyah.accountmanager.identity.domain.SpaceRole;

public record MembershipActor(UUID userId, UUID spaceId, String normalizedEmail, SpaceRole role) {
}
