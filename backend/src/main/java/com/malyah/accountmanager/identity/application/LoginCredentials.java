package com.malyah.accountmanager.identity.application;

public record LoginCredentials(String normalizedEmail, String passwordHash, boolean emailConfirmed) {
}
