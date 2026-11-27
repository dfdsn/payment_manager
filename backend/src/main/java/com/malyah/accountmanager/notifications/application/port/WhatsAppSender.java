package com.malyah.accountmanager.notifications.application.port;

import java.time.Duration;

/**
 * H08.4: the one call to the WhatsApp provider (the Evolution API since T36). Never called inside a database
 * transaction. The result tells apart an accepted request (with the provider message id), a refusal, a request that
 * certainly did not leave or was throttled, and a result nobody can prove either way; only {@link Outcome#ACCEPTED}
 * carries an id.
 */
public interface WhatsAppSender {
    SendResult send(Message message);

    enum Kind { SUMMARY, TEST }

    /**
     * {@code text} is the whole message, already composed (the test message has no financial data). H08.5:
     * {@code reference} is the attempt id; a provider that echoes it in its status events lets an uncertain attempt
     * be matched to its message later. It carries no personal or financial data. The Evolution API does not echo
     * it, so with that adapter it only identifies the attempt locally.
     */
    record Message(Kind kind, String recipientE164, String text, String reference) {
        public Message(Kind kind, String recipientE164, String text) {
            this(kind, recipientE164, text, null);
        }
    }

    enum Outcome { ACCEPTED, REJECTED, RECIPIENT_INVALID, UNAVAILABLE, UNCERTAIN }

    /**
     * {@code errorCode}: a short provider code or the HTTP status, never provider text (at most 16 characters).
     * {@code retryAfter}: a wait the provider asked for before trying again, when it said so ({@code null} otherwise).
     */
    record SendResult(Outcome outcome, String providerMessageId, String errorCode, Duration retryAfter) {
        public SendResult(Outcome outcome, String providerMessageId, String errorCode) {
            this(outcome, providerMessageId, errorCode, null);
        }

        public static SendResult accepted(String providerMessageId) {
            return new SendResult(Outcome.ACCEPTED, providerMessageId, null);
        }

        public static SendResult of(Outcome outcome, String errorCode) {
            return new SendResult(outcome, null, errorCode);
        }
    }
}
