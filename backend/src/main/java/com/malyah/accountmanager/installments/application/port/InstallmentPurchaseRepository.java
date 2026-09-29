package com.malyah.accountmanager.installments.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.malyah.accountmanager.installments.application.PurchaseClaim;
import com.malyah.accountmanager.installments.application.StoredInstallmentPurchase;
import com.malyah.accountmanager.installments.domain.InstallmentPlan;

public interface InstallmentPurchaseRepository {
    /** Claims the key; a concurrent request with the same key waits and then sees the first result. */
    PurchaseClaim claim(UUID spaceId, UUID actorId, UUID key, String requestHash, Instant at);

    void insert(UUID id, UUID spaceId, InstallmentPlan plan, UUID categoryId, UUID responsibleUserId, UUID actorId,
            Instant at);

    void complete(UUID spaceId, UUID actorId, UUID key, UUID purchaseId, Instant at);

    Optional<StoredInstallmentPurchase> find(UUID spaceId, UUID purchaseId);

    /** Newest purchases first. */
    List<StoredInstallmentPurchase> list(UUID spaceId, int offset, int limit);

    long count(UUID spaceId);
}
