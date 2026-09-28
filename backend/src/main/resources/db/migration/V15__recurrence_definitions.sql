CREATE TABLE recurrence_definitions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    description VARCHAR(200) NOT NULL CHECK (btrim(description) <> ''),
    amount NUMERIC(10,2) NOT NULL CHECK (amount BETWEEN 0.01 AND 99999999.99),
    value_type VARCHAR(24) NOT NULL CHECK (value_type IN ('FIXED', 'VARIABLE_ESTIMATE')),
    frequency VARCHAR(24) NOT NULL CHECK (frequency IN ('MONTHLY','BIMONTHLY','QUARTERLY','SEMIANNUAL','ANNUAL')),
    first_due_date DATE NOT NULL,
    base_day SMALLINT NOT NULL CHECK (base_day BETWEEN 1 AND 31),
    last_due_date DATE,
    category_id UUID REFERENCES expense_categories(id),
    responsible_user_id UUID REFERENCES identity_users(id),
    created_by_user_id UUID NOT NULL REFERENCES identity_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_recurrence_end CHECK (last_due_date IS NULL OR last_due_date >= first_due_date)
);

CREATE INDEX ix_recurrence_definitions_space ON recurrence_definitions(space_id, created_at, id);

CREATE TABLE recurrence_idempotency_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    recurrence_id UUID REFERENCES recurrence_definitions(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY(space_id, actor_user_id, idempotency_key)
);

CREATE TABLE recurrence_audit_events (
    id UUID PRIMARY KEY,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'RECURRENCE_CREATED'),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_recurrence_audit ON recurrence_audit_events(recurrence_id, occurred_at, id);
