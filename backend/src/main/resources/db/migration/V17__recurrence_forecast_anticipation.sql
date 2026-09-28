CREATE TABLE recurrence_anticipation_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    scheduled_due_date DATE NOT NULL,
    expense_id UUID REFERENCES expense_entries(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY(space_id,actor_user_id,idempotency_key),
    CHECK ((expense_id IS NULL AND completed_at IS NULL) OR (expense_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_recurrence_occurrences_space_due
    ON recurrence_occurrences(space_id,scheduled_due_date);
