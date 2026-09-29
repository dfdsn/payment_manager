package com.malyah.accountmanager.notifications.application.port;

import java.util.List;

/**
 * H08.4: the one call to the WhatsApp provider. Never called inside a database transaction. The result tells apart
 * an accepted request (with the provider message id), a refusal, a request that certainly did not leave or was
 * throttled, and a result nobody can prove either way; only {@link Outcome#ACCEPTED} carries an id.
 */
public interface WhatsAppSender {
    SendResult send(Message message);

    enum Kind { SUMMARY, TEST }

    /** {@code parameters} are the template body parameters; the test template has none. */
    record Message(Kind kind, String recipientE164, List<String> parameters) {
        public Message {
            parameters = List.copyOf(parameters);
        }
    }

    enum Outcome { ACCEPTED, REJECTED, RECIPIENT_INVALID, UNAVAILABLE, UNCERTAIN }

    /** {@code errorCode}: the provider's numeric code or the HTTP status, never provider text. */
    record SendResult(Outcome outcome, String providerMessageId, String errorCode) {
        public static SendResult accepted(String providerMessageId) {
            return new SendResult(Outcome.ACCEPTED, providerMessageId, null);
        }

        public static SendResult of(Outcome outcome, String errorCode) {
            return new SendResult(outcome, null, errorCode);
        }
    }
}
