-- H05.3: group changes and cancellation of pending installments of a purchase.
-- Paid and cancelled installments are never rewritten; each applied operation is one audited change.

CREATE TABLE installment_purchase_changes (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL REFERENCES installment_purchases(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    change_type VARCHAR(16) NOT NULL CHECK (change_type IN ('CHANGE', 'CANCELLATION')),
    scope VARCHAR(24) CHECK (scope IN ('THIS', 'THIS_AND_FOLLOWING')),
    from_number SMALLINT CHECK (from_number BETWEEN 1 AND 360),
    changed_fields VARCHAR(255),
    reason VARCHAR(2000) CHECK (reason IS NULL OR char_length(btrim(reason)) BETWEEN 1 AND 2000),
    impact_hash CHAR(64) NOT NULL,
    affected_count INTEGER NOT NULL CHECK (affected_count > 0),
    preserved_count INTEGER NOT NULL CHECK (preserved_count >= 0),
    replacement_purchase_id UUID REFERENCES installment_purchases(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK (
        (change_type = 'CHANGE' AND scope IS NOT NULL AND from_number IS NOT NULL
            AND changed_fields IS NOT NULL AND char_length(btrim(changed_fields)) > 0
            AND reason IS NULL AND replacement_purchase_id IS NULL)
        OR
        (change_type = 'CANCELLATION' AND scope IS NULL AND from_number IS NULL AND changed_fields IS NULL
            AND reason IS NOT NULL)
    )
);
CREATE INDEX ix_installment_purchase_changes ON installment_purchase_changes(space_id, purchase_id, occurred_at, id);

CREATE TABLE installment_change_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    change_id UUID REFERENCES installment_purchase_changes(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, idempotency_key),
    CHECK ((change_id IS NULL AND completed_at IS NULL) OR (change_id IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_installment_change_requests_created_at ON installment_change_requests(created_at);

-- A purchase created with the remainder of cancelled installments (RF-PAR-07) points to the one it replaces.
ALTER TABLE installment_purchases ADD COLUMN replaces_purchase_id UUID REFERENCES installment_purchases(id);
ALTER TABLE installment_purchases ADD CONSTRAINT ck_installment_purchase_not_self_replacing
    CHECK (replaces_purchase_id IS NULL OR replaces_purchase_id <> id);

ALTER TABLE expense_correction_events ADD COLUMN installment_change_id UUID REFERENCES installment_purchase_changes(id);
ALTER TABLE expense_correction_events ADD CONSTRAINT ck_expense_correction_single_origin
    CHECK (recurrence_change_id IS NULL OR installment_change_id IS NULL);
ALTER TABLE expense_cancellation_events ADD COLUMN installment_change_id UUID REFERENCES installment_purchase_changes(id);
ALTER TABLE expense_cancellation_events ADD CONSTRAINT ck_expense_cancellation_single_origin
    CHECK (recurrence_change_id IS NULL OR installment_change_id IS NULL);
