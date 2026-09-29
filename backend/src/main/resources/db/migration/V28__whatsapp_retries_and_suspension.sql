-- H08.5: retries of certain transient failures inside the slot window, reconciliation of uncertain results and
-- suspension of the channel after a permanent failure. V27 is not edited.

-- A delivery whose last attempt certainly did not reach Meta (connection refused, throttled) waits for its next
-- attempt of the same logical summary. It never has a provider id and always has the instant of the next attempt.
ALTER TABLE whatsapp_deliveries DROP CONSTRAINT whatsapp_deliveries_status_check;
ALTER TABLE whatsapp_deliveries ALTER COLUMN status TYPE VARCHAR(16);
ALTER TABLE whatsapp_deliveries ADD CONSTRAINT whatsapp_deliveries_status_check CHECK (status IN ('ATTEMPTING',
    'RETRY_WAITING', 'ACCEPTED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'REJECTED', 'UNCERTAIN', 'SKIPPED'));
ALTER TABLE whatsapp_deliveries ADD COLUMN next_attempt_at TIMESTAMPTZ;
-- An uncertain result later matched to its message by the webhook (the attempt id travels as callback data).
ALTER TABLE whatsapp_deliveries ADD COLUMN reconciled_at TIMESTAMPTZ;
ALTER TABLE whatsapp_deliveries ADD CONSTRAINT ck_whatsapp_delivery_retry CHECK (
    (status = 'RETRY_WAITING') = (next_attempt_at IS NOT NULL));
ALTER TABLE whatsapp_deliveries DROP CONSTRAINT ck_whatsapp_delivery_provider_id;
ALTER TABLE whatsapp_deliveries ADD CONSTRAINT ck_whatsapp_delivery_provider_id CHECK (
    (status IN ('ACCEPTED', 'SENT', 'DELIVERED', 'READ') AND provider_message_id IS NOT NULL)
    OR (status IN ('REJECTED', 'SKIPPED', 'ATTEMPTING', 'RETRY_WAITING') AND provider_message_id IS NULL)
    OR status IN ('FAILED', 'UNCERTAIN'));
CREATE INDEX ix_whatsapp_deliveries_retry ON whatsapp_deliveries(next_attempt_at) WHERE status = 'RETRY_WAITING';

-- Suspension after a permanent failure of the recipient or of the channel: the channel is off until the
-- administrator corrects it and enables it again (or saves another number). The consent is kept.
ALTER TABLE reminder_settings ADD COLUMN whatsapp_suspended_at TIMESTAMPTZ;
ALTER TABLE reminder_settings ADD COLUMN whatsapp_suspension_reason VARCHAR(32)
    CHECK (whatsapp_suspension_reason IN ('RECIPIENT_INVALID', 'PROVIDER_REJECTED'));
ALTER TABLE reminder_settings ADD CONSTRAINT ck_reminder_suspension CHECK (
    (whatsapp_suspended_at IS NULL) = (whatsapp_suspension_reason IS NULL)
    AND (whatsapp_suspended_at IS NULL OR NOT whatsapp_enabled));

-- The suspension is the one change made by the system itself, so it is the one event without an actor.
ALTER TABLE reminder_settings_events DROP CONSTRAINT reminder_settings_events_event_type_check;
ALTER TABLE reminder_settings_events ADD CONSTRAINT reminder_settings_events_event_type_check CHECK (event_type IN (
    'SCHEDULE_CHANGED', 'RECIPIENT_CHANGED', 'CONSENT_GRANTED', 'CONSENT_REVOKED', 'CHANNEL_ENABLED',
    'CHANNEL_DISABLED', 'CHANNEL_SUSPENDED'));
ALTER TABLE reminder_settings_events ALTER COLUMN actor_user_id DROP NOT NULL;
ALTER TABLE reminder_settings_events ADD CONSTRAINT ck_reminder_settings_event_actor CHECK (
    (actor_user_id IS NULL) = (event_type = 'CHANNEL_SUSPENDED'));
