package com.malyah.accountmanager.notifications.application;

import java.util.UUID;

/** H08.4, administrator only: the WhatsApp tracking of a summary and the test message to the consented number. */
public interface WhatsAppDeliveryUseCase {
    WhatsAppDeliveryView summaryDelivery(String actorEmail, UUID summaryId);

    /** Sends the test template once per key; repeating the key returns the first result without sending. */
    WhatsAppDeliveryView sendTest(String actorEmail, UUID idempotencyKey);
}
