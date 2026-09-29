-- H08.1: reminder schedule of the space and the administrator's WhatsApp channel (recipient, consent, activation).
-- One row per space, created on the first change; without it the defaults 09:00/18:00 apply and nothing is enabled.
-- Provider credentials never live here: they belong to the backend configuration (H08.4).
CREATE TABLE reminder_settings (
    space_id UUID PRIMARY KEY REFERENCES family_spaces(id),
    first_time TIME(0) NOT NULL DEFAULT '09:00',
    second_time TIME(0) NOT NULL DEFAULT '18:00',
    whatsapp_recipient VARCHAR(16) CHECK (whatsapp_recipient ~ '^\+55[1-9][0-9]9[0-9]{8}$'),
    whatsapp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID REFERENCES identity_users(id),
    CONSTRAINT ck_reminder_second_after_first CHECK (second_time > first_time),
    -- The channel can only be on with a recipient; the active consent is checked by the use case and below.
    CONSTRAINT ck_reminder_enabled_recipient CHECK (NOT whatsapp_enabled OR whatsapp_recipient IS NOT NULL)
);

-- Consent history: a grant is never rewritten except to record its revocation.
CREATE TABLE whatsapp_consents (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    user_id UUID NOT NULL REFERENCES identity_users(id),
    recipient VARCHAR(16) NOT NULL CHECK (recipient ~ '^\+55[1-9][0-9]9[0-9]{8}$'),
    consent_text_version VARCHAR(40) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id UUID REFERENCES identity_users(id),
    revocation_reason VARCHAR(32) CHECK (revocation_reason IN
        ('REVOKED_BY_ADMINISTRATOR', 'RECIPIENT_CHANGED', 'ADMINISTRATION_TRANSFERRED')),
    CONSTRAINT ck_whatsapp_consent_revocation CHECK (
        (revoked_at IS NULL AND revoked_by_user_id IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL AND revocation_reason IS NOT NULL
            AND revoked_at >= granted_at))
);
CREATE UNIQUE INDEX uq_whatsapp_consent_active ON whatsapp_consents(space_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_whatsapp_consents_space ON whatsapp_consents(space_id, granted_at DESC);

-- Audit of relevant changes, without the full phone number (masked) and without credentials.
CREATE TABLE reminder_settings_events (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('SCHEDULE_CHANGED', 'RECIPIENT_CHANGED', 'CONSENT_GRANTED',
        'CONSENT_REVOKED', 'CHANNEL_ENABLED', 'CHANNEL_DISABLED')),
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version > from_version),
    detail VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_reminder_settings_events_space ON reminder_settings_events(space_id, occurred_at DESC, to_version DESC);

CREATE TABLE reminder_settings_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    operation VARCHAR(32) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, operation, idempotency_key),
    CHECK ((result_version IS NULL AND completed_at IS NULL) OR (result_version IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_reminder_settings_requests_created_at ON reminder_settings_requests(created_at);
