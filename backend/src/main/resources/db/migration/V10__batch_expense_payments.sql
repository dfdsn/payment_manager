CREATE TABLE expense_batch_payment_operations (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    payer_user_id UUID NOT NULL REFERENCES identity_users(id),
    payment_date DATE NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (space_id, actor_user_id, idempotency_key),
    CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE TABLE expense_batch_payment_items (
    batch_operation_id UUID NOT NULL REFERENCES expense_batch_payment_operations(id),
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version = from_version + 1),
    paid_amount NUMERIC(10,2) NOT NULL CHECK (paid_amount > 0 AND paid_amount <= 99999999.99),
    PRIMARY KEY (batch_operation_id, expense_id)
);

ALTER TABLE expense_payment_events
    ADD COLUMN batch_operation_id UUID REFERENCES expense_batch_payment_operations(id);

CREATE INDEX ix_expense_payment_events_batch
    ON expense_payment_events(batch_operation_id) WHERE batch_operation_id IS NOT NULL;
