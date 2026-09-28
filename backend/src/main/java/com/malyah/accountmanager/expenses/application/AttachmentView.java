package com.malyah.accountmanager.expenses.application;

import java.time.Instant;
import java.util.UUID;

public record AttachmentView(UUID id, String name, String mediaType, long size, UUID uploadedByUserId,
        String uploadedByDisplayName, Instant uploadedAt) { }
