-- H04.4: confirmation of variable recurring charges.
-- The estimate replaced by a confirmation is preserved on the entry and in an immutable event.
ALTER TABLE expense_entries ADD COLUMN estimated_charge_amount NUMERIC(10,2)
    CHECK (estimated_charge_amount IS NULL OR (estimated_charge_amount > 0 AND estimated_charge_amount <= 99999999.99));
ALTER TABLE expense_entries ADD COLUMN charge_confirmed_at TIMESTAMPTZ;
ALTER TABLE expense_entries ADD COLUMN charge_confirmed_by_user_id UUID REFERENCES identity_users(id);
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_charge_confirmation_audit CHECK (
    (estimated_charge_amount IS NULL AND charge_confirmed_at IS NULL AND charge_confirmed_by_user_id IS NULL)
    OR
    (origin = 'RECURRENCE' AND charge_confirmed
        AND estimated_charge_amount IS NOT NULL
        AND charge_confirmed_at IS NOT NULL
        AND charge_confirmed_by_user_id IS NOT NULL)
);

CREATE TABLE expense_charge_events (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('CHARGE_CONFIRMED', 'ESTIMATE_UPDATED')),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version = from_version + 1),
    previous_amount NUMERIC(10,2) NOT NULL CHECK (previous_amount > 0 AND previous_amount <= 99999999.99),
    new_amount NUMERIC(10,2) NOT NULL CHECK (new_amount > 0 AND new_amount <= 99999999.99),
    source_expense_id UUID REFERENCES expense_entries(id),
    UNIQUE (expense_id, to_version),
    CHECK ((event_type = 'CHARGE_CONFIRMED' AND source_expense_id IS NULL)
        OR (event_type = 'ESTIMATE_UPDATED' AND source_expense_id IS NOT NULL AND source_expense_id <> expense_id))
);

-- A charge is confirmed at most once; later changes use the individual correction flow.
CREATE UNIQUE INDEX ux_expense_charge_confirmed_once
    ON expense_charge_events(expense_id) WHERE event_type = 'CHARGE_CONFIRMED';
CREATE INDEX ix_expense_charge_events_expense ON expense_charge_events(space_id, expense_id, occurred_at);
