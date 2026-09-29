package com.malyah.accountmanager.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Public cross-module contract invoked inside the administration transfer transaction (RF-ACC-10): what belonged to
 * the previous administrator personally, such as the WhatsApp number and consent, stops being usable. A failure
 * rolls the whole transfer back.
 */
public interface AdministrationTransferHandler {
    void afterAdministrationTransferred(UUID spaceId, UUID previousAdministratorId, UUID newAdministratorId,
            Instant occurredAt);
}
