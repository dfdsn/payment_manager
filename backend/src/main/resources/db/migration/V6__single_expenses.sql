CREATE TABLE expense_entries (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    origin VARCHAR(24) NOT NULL CHECK (origin = 'ONE_OFF'),
    description VARCHAR(200) NOT NULL CHECK (char_length(btrim(description)) BETWEEN 1 AND 200),
    charge_amount NUMERIC(10,2) NOT NULL CHECK (charge_amount > 0 AND charge_amount <= 99999999.99),
    charge_confirmed BOOLEAN NOT NULL DEFAULT TRUE CHECK (charge_confirmed),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PAID')),
    due_date DATE,
    reference_date DATE NOT NULL,
    notes VARCHAR(2000) CHECK (notes IS NULL OR char_length(notes) <= 2000),
    paid_amount NUMERIC(10,2) CHECK (paid_amount > 0 AND paid_amount <= 99999999.99),
    payment_date DATE,
    paid_by_user_id UUID REFERENCES identity_users(id),
    payment_recorded_by_user_id UUID REFERENCES identity_users(id),
    created_by_user_id UUID NOT NULL REFERENCES identity_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_expense_status_dates CHECK (
        (status = 'PENDING'
            AND due_date IS NOT NULL
            AND reference_date = due_date
            AND paid_amount IS NULL
            AND payment_date IS NULL
            AND paid_by_user_id IS NULL
            AND payment_recorded_by_user_id IS NULL)
        OR
        (status = 'PAID'
            AND paid_amount IS NOT NULL
            AND payment_date IS NOT NULL
            AND paid_by_user_id IS NOT NULL
            AND payment_recorded_by_user_id IS NOT NULL
            AND reference_date = COALESCE(due_date, payment_date))
    )
);

CREATE INDEX ix_expense_entries_space_reference
    ON expense_entries(space_id, reference_date, created_at, id);

CREATE TABLE expense_idempotency_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    operation VARCHAR(48) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expense_id UUID REFERENCES expense_entries(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, operation, idempotency_key),
    CHECK ((expense_id IS NULL AND completed_at IS NULL)
        OR (expense_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_expense_idempotency_created_at
    ON expense_idempotency_requests(created_at);
