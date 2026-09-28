-- H05.1: purchases split into a finite number of monthly installments.
-- The purchase is an aggregate header, never an expense: only its installment entries are expenses.
CREATE TABLE installment_purchases (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    description VARCHAR(200) NOT NULL CHECK (char_length(btrim(description)) BETWEEN 1 AND 200),
    total_amount NUMERIC(10,2) NOT NULL CHECK (total_amount > 0 AND total_amount <= 99999999.99),
    installment_count SMALLINT NOT NULL CHECK (installment_count BETWEEN 2 AND 360),
    first_due_date DATE NOT NULL,
    category_id UUID REFERENCES expense_categories(id),
    responsible_user_id UUID REFERENCES identity_users(id),
    created_by_user_id UUID NOT NULL REFERENCES identity_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    -- Every installment must be at least R$ 0,01.
    CHECK (total_amount * 100 >= installment_count)
);
CREATE INDEX ix_installment_purchases_space ON installment_purchases(space_id, created_at, id);

CREATE TABLE installment_purchase_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    purchase_id UUID REFERENCES installment_purchases(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, idempotency_key),
    CHECK ((purchase_id IS NULL AND completed_at IS NULL) OR (purchase_id IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_installment_purchase_requests_created_at ON installment_purchase_requests(created_at);

CREATE TABLE installment_purchase_events (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL REFERENCES installment_purchases(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'PURCHASE_CREATED'),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (purchase_id, event_type)
);

ALTER TABLE expense_entries DROP CONSTRAINT ck_expense_origin;
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_origin
    CHECK (origin IN ('ONE_OFF', 'RECURRENCE', 'INSTALLMENT'));
ALTER TABLE expense_entries DROP CONSTRAINT ck_expense_charge_confirmation;
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_charge_confirmation CHECK (
    (origin IN ('ONE_OFF', 'INSTALLMENT') AND charge_confirmed) OR origin = 'RECURRENCE'
);

ALTER TABLE expense_entries ADD COLUMN installment_purchase_id UUID REFERENCES installment_purchases(id);
ALTER TABLE expense_entries ADD COLUMN installment_number SMALLINT;
ALTER TABLE expense_entries ADD COLUMN installment_count SMALLINT;
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_installment CHECK (
    (origin = 'INSTALLMENT'
        AND installment_purchase_id IS NOT NULL
        AND installment_count BETWEEN 2 AND 360
        AND installment_number BETWEEN 1 AND installment_count)
    OR
    (origin <> 'INSTALLMENT'
        AND installment_purchase_id IS NULL
        AND installment_number IS NULL
        AND installment_count IS NULL)
);
ALTER TABLE expense_entries ADD CONSTRAINT uq_expense_installment_number
    UNIQUE (installment_purchase_id, installment_number);
