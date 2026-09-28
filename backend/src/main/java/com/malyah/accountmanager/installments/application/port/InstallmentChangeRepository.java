package com.malyah.accountmanager.installments.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.malyah.accountmanager.installments.application.ChangeClaim;
import com.malyah.accountmanager.installments.application.StoredInstallmentChange;

public interface InstallmentChangeRepository {
    /** Locks the purchase header, serializing changes of the same purchase; false when it is not in the space. */
    boolean lockPurchase(UUID spaceId, UUID purchaseId);

    /** Claims the key; a concurrent request with the same key waits and then sees the first result. */
    ChangeClaim claim(UUID spaceId, UUID actorId, UUID key, String requestHash, Instant at);

    void insert(StoredInstallmentChange change, UUID spaceId, UUID actorId, String scope, Integer fromNumber,
            String changedFields, String reason, String impactHash, Instant at);

    void complete(UUID spaceId, UUID actorId, UUID key, UUID changeId, Instant at);

    Optional<StoredInstallmentChange> find(UUID spaceId, UUID changeId);

    void markReplacement(UUID spaceId, UUID replacementId, UUID replacedId);
}
