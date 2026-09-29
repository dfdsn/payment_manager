package com.malyah.accountmanager.notifications.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.notifications.application.StoredNotification;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;

/**
 * H08.3 persistence of the in-app notifications; every call runs in the caller's transaction. Reads and writes
 * always carry the space and the recipient, and administrative notifications are only reachable when the caller
 * says the reader is the administrator now.
 */
public interface MemberNotificationRepository {
    /** One summary notification per member active now; a repeated call creates nothing. Returns how many were new. */
    int deliverSummary(UUID spaceId, UUID summaryId, Instant at);

    /**
     * The failure notification of a summary for the active administrator; a later failure of the same summary
     * replaces the reason instead of adding a notification. False when the space has no active administrator.
     */
    boolean recordWhatsAppFailure(UUID spaceId, UUID summaryId, WhatsAppFailureReason reason, Instant at);

    List<StoredNotification> page(UUID spaceId, UUID recipientId, boolean administrator, boolean dismissed,
            long offset, int limit);

    long count(UUID spaceId, UUID recipientId, boolean administrator, boolean dismissed);

    long unread(UUID spaceId, UUID recipientId, boolean administrator);

    /** Sets the first read instant (kept on repetition); empty when the notification is not the reader's. */
    Optional<StoredNotification> markRead(UUID spaceId, UUID recipientId, boolean administrator, UUID id, Instant at);

    /** Dismisses (and reads) the notification; the first instants are kept on repetition. */
    Optional<StoredNotification> markDismissed(UUID spaceId, UUID recipientId, boolean administrator, UUID id,
            Instant at);
}
