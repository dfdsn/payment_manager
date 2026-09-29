package com.malyah.accountmanager.notifications.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.malyah.accountmanager.notifications.application.StoredDelivery;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Kind;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.WhatsAppDeliveryStatus;

/**
 * H08.4 persistence of the WhatsApp deliveries; every call runs in the caller's transaction. A summary has at most
 * one delivery (unique key), so concurrent workers and re-executions never send the same summary twice.
 */
public interface WhatsAppDeliveryRepository {
    /** Summaries whose WhatsApp channel was planned and that have no delivery yet, oldest first. */
    List<UUID> plannedSummaries(int limit);

    /** The planned WhatsApp channel of a summary still without delivery. */
    Optional<PlannedSummary> planned(UUID summaryId);

    /** Records a new delivery; false when the summary (or the test key) already has one. */
    boolean insert(NewDelivery delivery);

    void insertAttempt(UUID attemptId, UUID deliveryId, int number, Instant at);

    /**
     * Records the result of the attempt: the delivery moves from {@code ATTEMPTING} (or from an {@code UNCERTAIN}
     * without provider id, when a late answer arrives) to {@code status}. False when nothing matched.
     */
    boolean finish(UUID deliveryId, UUID attemptId, WhatsAppDeliveryStatus status, String providerMessageId,
            String providerErrorCode, String failureCode, Instant at);

    /** Deliveries still {@code ATTEMPTING} since before {@code before}: the process stopped mid-call. */
    List<StaleDelivery> staleAttempts(Instant before);

    /** Locks the delivery that the provider knows by {@code providerMessageId}. */
    Optional<DeliveryRef> lockByProviderMessageId(String providerMessageId);

    /** Whether any delivery is waiting for the provider's answer (its id may not be recorded yet). */
    boolean anyAttempting();

    /** Records a webhook status once per message and status; false when it was already recorded. */
    boolean recordEvent(String providerMessageId, UUID deliveryId, WhatsAppDeliveryStatus status, Instant providerAt,
            String providerErrorCode, Instant receivedAt);

    /** Fills the instant of a confirmation (first one kept) and, when {@code advance}, moves the state. */
    void confirm(UUID deliveryId, WhatsAppDeliveryStatus status, Instant providerAt, String providerErrorCode,
            boolean advance, Instant at);

    Optional<StoredDelivery> forSummary(UUID spaceId, UUID summaryId);

    Optional<StoredDelivery> test(UUID spaceId, UUID key);

    Optional<Instant> lastTestAt(UUID spaceId);

    record PlannedSummary(UUID summaryId, UUID spaceId, UUID recipientUserId, LocalDate date, ReminderSlot slot,
            LocalTime scheduledTime, String timeZone, Instant scheduledAt) { }

    record NewDelivery(UUID id, UUID spaceId, Kind kind, UUID summaryId, UUID testKey, UUID recipientUserId,
            UUID consentId, String recipientLastDigits, WhatsAppDeliveryStatus status, String skipReason,
            String failureCode, Integer itemCount, Instant at) { }

    record StaleDelivery(UUID id, UUID attemptId, UUID spaceId, UUID summaryId) { }

    record DeliveryRef(UUID id, UUID spaceId, UUID summaryId, WhatsAppDeliveryStatus status) { }
}
