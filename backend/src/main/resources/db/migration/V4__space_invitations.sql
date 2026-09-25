CREATE TABLE space_invitations (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    invited_email VARCHAR(254) NOT NULL,
    invited_by_user_id UUID NOT NULL REFERENCES identity_users(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE UNIQUE INDEX uq_space_invitation_active
    ON space_invitations(space_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_space_invitations_email
    ON space_invitations(invited_email, created_at DESC);
