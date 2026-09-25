package com.malyah.accountmanager.identity.application;

import java.util.UUID;

import com.malyah.accountmanager.identity.domain.SpaceRole;

public record InvitationActor(UUID userId, UUID spaceId, SpaceRole role) {
}
