ALTER TABLE space_memberships
    ADD COLUMN ended_at TIMESTAMPTZ,
    ADD COLUMN ended_by_user_id UUID REFERENCES identity_users(id),
    ADD COLUMN end_reason VARCHAR(24),
    ADD CONSTRAINT ck_membership_lifecycle CHECK (
        (active AND ended_at IS NULL AND ended_by_user_id IS NULL AND end_reason IS NULL)
        OR
        (NOT active AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL
            AND end_reason IN ('VOLUNTARY_EXIT', 'ADMIN_REMOVAL'))
    );

CREATE TABLE membership_lifecycle_events (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    subject_user_id UUID NOT NULL REFERENCES identity_users(id),
    event_type VARCHAR(32) NOT NULL CHECK (
        event_type IN ('MEMBER_LEFT', 'MEMBER_REMOVED', 'ADMINISTRATION_TRANSFERRED')
    ),
    actor_previous_role VARCHAR(24) NOT NULL CHECK (actor_previous_role IN ('ADMINISTRATOR', 'GUEST')),
    actor_new_role VARCHAR(24) CHECK (actor_new_role IN ('ADMINISTRATOR', 'GUEST')),
    subject_previous_role VARCHAR(24) NOT NULL CHECK (subject_previous_role IN ('ADMINISTRATOR', 'GUEST')),
    subject_new_role VARCHAR(24) CHECK (subject_new_role IN ('ADMINISTRATOR', 'GUEST')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_membership_lifecycle_events_space
    ON membership_lifecycle_events(space_id, occurred_at DESC);
