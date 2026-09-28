package com.malyah.accountmanager.recurrences.application;

import java.util.UUID;

public record ChangeClaim(boolean replayed, UUID changeId) { }
