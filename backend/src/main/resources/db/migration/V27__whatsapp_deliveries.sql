-- H08.4: WhatsApp deliveries through the Meta Cloud API. One logical delivery per summary (identity space + date +
-- slot + channel) or per administrator test key; attempts and provider confirmations are separate rows. Only the
-- last four digits of the number are kept here and no provider text, token or message content is stored.
CREATE TABLE whatsapp_deliveries (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('SUMMARY', 'TEST')),
    summary_id UUID REFERENCES reminder_summaries(id),
    test_key UUID,
    recipient_user_id UUID NOT NULL REFERENCES identity_users(id),
    consent_id UUID REFERENCES whatsapp_consents(id),
    recipient_last_digits CHAR(4) CHECK (recipient_last_digits ~ '^[0-9]{4}$'),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ATTEMPTING', 'ACCEPTED', 'SENT', 'DELIVERED', 'READ', 'FAILED',
        'REJECTED', 'UNCERTAIN', 'SKIPPED')),
    skip_reason VARCHAR(32),
    failure_code VARCHAR(32),
    provider_error_code VARCHAR(16),
    provider_message_id VARCHAR(128),
    item_count INTEGER CHECK (item_count > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    attempted_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    CONSTRAINT uq_whatsapp_delivery_summary UNIQUE (summary_id),
    CONSTRAINT uq_whatsapp_delivery_test UNIQUE (space_id, test_key),
    CONSTRAINT uq_whatsapp_delivery_provider_message UNIQUE (provider_message_id),
    CONSTRAINT ck_whatsapp_delivery_kind CHECK (
        (kind = 'SUMMARY' AND summary_id IS NOT NULL AND test_key IS NULL)
        OR (kind = 'TEST' AND summary_id IS NULL AND test_key IS NOT NULL)),
    CONSTRAINT ck_whatsapp_delivery_skip CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL)),
    -- Only a skipped delivery never reached the attempt: every other one has the consent and number it used.
    CONSTRAINT ck_whatsapp_delivery_attempt CHECK (
        status = 'SKIPPED' OR (consent_id IS NOT NULL AND recipient_last_digits IS NOT NULL AND attempted_at IS NOT NULL)),
    -- Acceptance and every later confirmation need the provider message id; a refusal never has one.
    CONSTRAINT ck_whatsapp_delivery_provider_id CHECK (
        (status IN ('ACCEPTED', 'SENT', 'DELIVERED', 'READ') AND provider_message_id IS NOT NULL)
        OR (status IN ('REJECTED', 'SKIPPED', 'ATTEMPTING') AND provider_message_id IS NULL)
        OR status IN ('FAILED', 'UNCERTAIN'))
);
CREATE INDEX ix_whatsapp_deliveries_attempting ON whatsapp_deliveries(attempted_at) WHERE status = 'ATTEMPTING';
CREATE INDEX ix_whatsapp_deliveries_tests ON whatsapp_deliveries(space_id, created_at DESC) WHERE kind = 'TEST';

-- Each call to the provider. H08.4 makes one attempt per delivery; H08.5 retries add numbers to the same delivery.
CREATE TABLE whatsapp_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES whatsapp_deliveries(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(10) CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'FAILED', 'UNCERTAIN')),
    provider_error_code VARCHAR(16),
    CONSTRAINT uq_whatsapp_attempt_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT ck_whatsapp_attempt_finished CHECK ((finished_at IS NULL) = (outcome IS NULL))
);

-- Webhook statuses of known messages, once per message and status: repeated deliveries by Meta are dropped here.
CREATE TABLE whatsapp_status_events (
    provider_message_id VARCHAR(128) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    delivery_id UUID NOT NULL REFERENCES whatsapp_deliveries(id),
    provider_at TIMESTAMPTZ NOT NULL,
    provider_error_code VARCHAR(16),
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider_message_id, status)
);
CREATE INDEX ix_whatsapp_status_events_delivery ON whatsapp_status_events(delivery_id);
