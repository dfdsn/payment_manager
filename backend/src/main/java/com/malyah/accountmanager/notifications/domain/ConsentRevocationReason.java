package com.malyah.accountmanager.notifications.domain;

/** Why a WhatsApp consent stopped being usable; the grant itself is never rewritten. */
public enum ConsentRevocationReason {
    REVOKED_BY_ADMINISTRATOR, RECIPIENT_CHANGED, ADMINISTRATION_TRANSFERRED
}
