package com.malyah.accountmanager.identity.application;

import java.util.UUID;

public record UserAccessAccount(UUID id, String normalizedEmail, boolean emailConfirmed) {
}
