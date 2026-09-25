package com.malyah.accountmanager.identity.application;

import java.util.UUID;

import com.malyah.accountmanager.identity.domain.SpaceRole;

public record ManagedMember(UUID userId, String displayName, String email, SpaceRole role, boolean currentUser) {
}
