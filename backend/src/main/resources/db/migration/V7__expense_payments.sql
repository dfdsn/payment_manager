ALTER TABLE expense_entries ADD COLUMN payment_notes VARCHAR(2000);
ALTER TABLE expense_entries ADD COLUMN payment_recorded_at TIMESTAMPTZ;
UPDATE expense_entries SET payment_recorded_at = created_at WHERE status = 'PAID';

CREATE TABLE expense_payment_events (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'EXPENSE_PAID'),
    charge_amount NUMERIC(10,2) NOT NULL CHECK (charge_amount > 0),
    paid_amount NUMERIC(10,2) NOT NULL CHECK (paid_amount > 0),
    payment_date DATE NOT NULL,
    payer_user_id UUID NOT NULL REFERENCES identity_users(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    notes VARCHAR(2000),
    recorded_at TIMESTAMPTZ NOT NULL,
    expense_version BIGINT NOT NULL,
    UNIQUE(expense_id, expense_version)
);

-- Preserve the original author and timestamp of paid entries created before V7.
INSERT INTO expense_payment_events
SELECT id, id, space_id, 'EXPENSE_PAID', charge_amount, paid_amount, payment_date,
       paid_by_user_id, payment_recorded_by_user_id, NULL, created_at, version
  FROM expense_entries WHERE status = 'PAID';
