ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_origin_check;
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_origin CHECK (origin IN ('ONE_OFF','RECURRENCE'));
ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_charge_confirmed_check;
ALTER TABLE expense_entries ADD CONSTRAINT ck_expense_charge_confirmation CHECK (
    (origin='ONE_OFF' AND charge_confirmed) OR origin='RECURRENCE'
);

CREATE TABLE recurrence_generation_jobs (
    id UUID PRIMARY KEY,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    scheduled_due_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','FAILED','COMPLETED')),
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code VARCHAR(100),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(recurrence_id, scheduled_due_date),
    CHECK ((status='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX ix_recurrence_generation_eligible ON recurrence_generation_jobs(status,available_at,lease_until);

CREATE TABLE recurrence_occurrences (
    id UUID PRIMARY KEY,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    scheduled_due_date DATE NOT NULL,
    expense_id UUID UNIQUE REFERENCES expense_entries(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(recurrence_id,scheduled_due_date)
);

CREATE TABLE recurrence_occurrence_events (
    id UUID PRIMARY KEY,
    occurrence_id UUID NOT NULL REFERENCES recurrence_occurrences(id),
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    expense_id UUID NOT NULL REFERENCES expense_entries(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    event_type VARCHAR(40) NOT NULL CHECK(event_type='OCCURRENCE_MATERIALIZED'),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE(occurrence_id,event_type)
);
