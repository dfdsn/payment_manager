CREATE TABLE expense_correction_events (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    corrected_at TIMESTAMPTZ NOT NULL,
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version = from_version + 1),
    changed_fields VARCHAR(255) NOT NULL CHECK (char_length(btrim(changed_fields)) > 0),
    old_description VARCHAR(200) NOT NULL,
    new_description VARCHAR(200) NOT NULL,
    old_charge_amount NUMERIC(10,2) NOT NULL,
    new_charge_amount NUMERIC(10,2) NOT NULL,
    old_due_date DATE,
    new_due_date DATE,
    old_notes VARCHAR(2000),
    new_notes VARCHAR(2000),
    old_paid_amount NUMERIC(10,2),
    new_paid_amount NUMERIC(10,2),
    old_payment_date DATE,
    new_payment_date DATE,
    old_payer_user_id UUID REFERENCES identity_users(id),
    new_payer_user_id UUID REFERENCES identity_users(id),
    old_payment_notes VARCHAR(2000),
    new_payment_notes VARCHAR(2000),
    UNIQUE (expense_id, to_version)
);

CREATE INDEX ix_expense_correction_events_expense
    ON expense_correction_events(expense_id, to_version);
