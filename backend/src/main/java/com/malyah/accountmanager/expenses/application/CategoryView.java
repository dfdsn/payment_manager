package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.util.UUID;

public record CategoryView(UUID id, String name, boolean archived, long version, Instant updatedAt) {}
