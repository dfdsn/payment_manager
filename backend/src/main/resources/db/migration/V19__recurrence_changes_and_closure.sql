-- H04.5: changes with period validity ("este e os próximos") and closure of recurrences.
-- The definition keeps its identity; each segment governs the months from its effective month onwards.

CREATE TABLE recurrence_change_events (
    id UUID PRIMARY KEY,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    change_type VARCHAR(16) NOT NULL CHECK (change_type IN ('CHANGE', 'CLOSURE')),
    occurred_at TIMESTAMPTZ NOT NULL,
    from_version BIGINT NOT NULL CHECK (from_version >= 0),
    to_version BIGINT NOT NULL CHECK (to_version = from_version + 1),
    effective_due_date DATE NOT NULL,
    changed_fields VARCHAR(255) NOT NULL CHECK (char_length(btrim(changed_fields)) > 0),
    previous_configuration TEXT NOT NULL,
    new_configuration TEXT NOT NULL,
    reason VARCHAR(2000) CHECK (reason IS NULL OR char_length(btrim(reason)) BETWEEN 1 AND 2000),
    impact_hash CHAR(64) NOT NULL,
    updated_count INTEGER NOT NULL CHECK (updated_count >= 0),
    removed_count INTEGER NOT NULL CHECK (removed_count >= 0),
    review_count INTEGER NOT NULL CHECK (review_count >= 0),
    preserved_count INTEGER NOT NULL CHECK (preserved_count >= 0),
    UNIQUE (recurrence_id, to_version),
    CHECK (change_type = 'CHANGE' OR reason IS NOT NULL)
);
CREATE INDEX ix_recurrence_change_events ON recurrence_change_events(space_id, recurrence_id, occurred_at, id);

CREATE TABLE recurrence_segments (
    id UUID PRIMARY KEY,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    effective_month DATE NOT NULL CHECK (EXTRACT(DAY FROM effective_month) = 1),
    base_day SMALLINT NOT NULL CHECK (base_day BETWEEN 1 AND 31),
    frequency VARCHAR(24) NOT NULL CHECK (frequency IN ('MONTHLY','BIMONTHLY','QUARTERLY','SEMIANNUAL','ANNUAL')),
    description VARCHAR(200) NOT NULL CHECK (btrim(description) <> ''),
    amount NUMERIC(10,2) NOT NULL CHECK (amount BETWEEN 0.01 AND 99999999.99),
    estimate_reset BOOLEAN NOT NULL,
    category_id UUID REFERENCES expense_categories(id),
    responsible_user_id UUID REFERENCES identity_users(id),
    change_id UUID REFERENCES recurrence_change_events(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (recurrence_id, effective_month)
);

INSERT INTO recurrence_segments(id, recurrence_id, space_id, effective_month, base_day, frequency, description,
        amount, estimate_reset, category_id, responsible_user_id, change_id, created_at)
SELECT gen_random_uuid(), r.id, r.space_id, date_trunc('month', r.first_due_date)::date, r.base_day, r.frequency,
       r.description, r.amount, true, r.category_id, r.responsible_user_id, NULL, r.created_at
  FROM recurrence_definitions r;

ALTER TABLE recurrence_definitions
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN closed_by_user_id UUID REFERENCES identity_users(id),
    ADD COLUMN closure_reason VARCHAR(2000),
    ADD CONSTRAINT ck_recurrence_closure CHECK (
        (closed_at IS NULL AND closed_by_user_id IS NULL AND closure_reason IS NULL)
        OR (closed_at IS NOT NULL AND closed_by_user_id IS NOT NULL AND last_due_date IS NOT NULL
            AND char_length(btrim(closure_reason)) BETWEEN 1 AND 2000));

-- The logical identity of an occurrence is its period: at most one occurrence per recurrence and month,
-- even after the due day or frequency changes.
ALTER TABLE recurrence_occurrences
    ADD COLUMN scheduled_month DATE GENERATED ALWAYS AS ((date_trunc('month', scheduled_due_date::timestamp))::date) STORED,
    ADD COLUMN review_reason VARCHAR(24) CHECK (review_reason IN ('AFTER_END', 'OUTSIDE_SCHEDULE')),
    ADD COLUMN review_change_id UUID REFERENCES recurrence_change_events(id),
    ADD CONSTRAINT ck_recurrence_occurrence_review CHECK ((review_reason IS NULL) = (review_change_id IS NULL));
CREATE UNIQUE INDEX ux_recurrence_occurrence_month ON recurrence_occurrences(recurrence_id, scheduled_month);

ALTER TABLE recurrence_generation_jobs DROP CONSTRAINT recurrence_generation_jobs_status_check;
ALTER TABLE recurrence_generation_jobs ADD CONSTRAINT ck_recurrence_generation_status
    CHECK (status IN ('PENDING','PROCESSING','FAILED','COMPLETED','SKIPPED'));

CREATE TABLE recurrence_change_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    recurrence_id UUID NOT NULL REFERENCES recurrence_definitions(id),
    change_id UUID REFERENCES recurrence_change_events(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, idempotency_key),
    CHECK ((change_id IS NULL AND completed_at IS NULL) OR (change_id IS NOT NULL AND completed_at IS NOT NULL))
);

-- Expense-side audit of changes applied by a recurrence change or closure.
ALTER TABLE expense_correction_events ADD COLUMN recurrence_change_id UUID REFERENCES recurrence_change_events(id);
ALTER TABLE expense_cancellation_events ADD COLUMN recurrence_change_id UUID REFERENCES recurrence_change_events(id);
