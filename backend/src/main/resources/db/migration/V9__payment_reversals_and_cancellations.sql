ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_status_check;
ALTER TABLE expense_entries DROP CONSTRAINT ck_expense_status_dates;

ALTER TABLE expense_entries ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE expense_entries ADD COLUMN cancelled_by_user_id UUID REFERENCES identity_users(id);
ALTER TABLE expense_entries ADD COLUMN cancellation_reason VARCHAR(2000);

ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_status
    CHECK (status IN ('PENDING', 'PAID', 'CANCELLED'));

ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_status_dates CHECK (
    (status = 'PENDING'
        AND due_date IS NOT NULL
        AND reference_date = due_date
        AND paid_amount IS NULL
        AND payment_date IS NULL
        AND paid_by_user_id IS NULL
        AND payment_recorded_by_user_id IS NULL
        AND payment_recorded_at IS NULL
        AND payment_notes IS NULL
        AND cancelled_at IS NULL
        AND cancelled_by_user_id IS NULL
        AND cancellation_reason IS NULL)
    OR
    (status = 'PAID'
        AND paid_amount IS NOT NULL
        AND payment_date IS NOT NULL
        AND paid_by_user_id IS NOT NULL
        AND payment_recorded_by_user_id IS NOT NULL
        AND payment_recorded_at IS NOT NULL
        AND reference_date = COALESCE(due_date, payment_date)
        AND cancelled_at IS NULL
        AND cancelled_by_user_id IS NULL
        AND cancellation_reason IS NULL)
    OR
    (status = 'CANCELLED'
        AND due_date IS NOT NULL
        AND reference_date = due_date
        AND paid_amount IS NULL
        AND payment_date IS NULL
        AND paid_by_user_id IS NULL
        AND payment_recorded_by_user_id IS NULL
        AND payment_recorded_at IS NULL
        AND payment_notes IS NULL
        AND cancelled_at IS NOT NULL
        AND cancelled_by_user_id IS NOT NULL
        AND cancellation_reason IS NOT NULL
        AND char_length(btrim(cancellation_reason)) BETWEEN 1 AND 2000)
);

ALTER TABLE expense_payment_events DROP CONSTRAINT expense_payment_events_event_type_check;
ALTER TABLE expense_payment_events ADD COLUMN reason VARCHAR(2000);
ALTER TABLE expense_payment_events ADD CONSTRAINT ck_expense_payment_event_type
    CHECK (event_type IN ('EXPENSE_PAID', 'PAYMENT_REVERSED'));
ALTER TABLE expense_payment_events ADD CONSTRAINT ck_expense_payment_event_reason
    CHECK ((event_type = 'EXPENSE_PAID' AND reason IS NULL)
        OR (event_type = 'PAYMENT_REVERSED' AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 1 AND 2000));

CREATE TABLE expense_cancellation_events (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    reason VARCHAR(2000) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 2000),
    cancelled_at TIMESTAMPTZ NOT NULL,
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version = from_version + 1),
    UNIQUE (expense_id, to_version)
);

CREATE INDEX ix_expense_cancellation_events_expense
    ON expense_cancellation_events(expense_id, to_version);
