CREATE TABLE identity_access_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_users(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('CONFIRM_EMAIL', 'RESET_PASSWORD')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_identity_access_tokens_user_purpose
    ON identity_access_tokens(user_id, purpose, created_at DESC);

CREATE UNIQUE INDEX uq_identity_access_token_active_purpose
    ON identity_access_tokens(user_id, purpose)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
