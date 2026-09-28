package com.malyah.accountmanager.installments.application;

import java.util.UUID;

public record ChangeClaim(boolean replayed, UUID changeId) { }
