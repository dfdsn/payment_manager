-- H08.2: processed reminder slots and logical summaries. At most one summary per space, local date and slot
-- (RF-ALT-08/15) and one record per channel; nothing here is a delivery attempt (H08.3/H08.4).

-- Every slot handled by the job, with or without a summary: EMPTY (no eligible bill, nothing to send) and MISSED
-- (window over before processing, never sent late) keep a re-execution from producing a summary afterwards.
CREATE TABLE reminder_slot_runs (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    local_date DATE NOT NULL,
    slot VARCHAR(6) NOT NULL CHECK (slot IN ('FIRST', 'SECOND')),
    scheduled_time TIME(0) NOT NULL,
    outcome VARCHAR(9) NOT NULL CHECK (outcome IN ('GENERATED', 'EMPTY', 'MISSED')),
    summary_id UUID,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (space_id, local_date, slot),
    CONSTRAINT ck_reminder_slot_run_summary CHECK ((outcome = 'GENERATED') = (summary_id IS NOT NULL))
);

CREATE TABLE reminder_summaries (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    local_date DATE NOT NULL,
    slot VARCHAR(6) NOT NULL CHECK (slot IN ('FIRST', 'SECOND')),
    scheduled_time TIME(0) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL CHECK (generated_at >= scheduled_at),
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    total_amount NUMERIC(14,2) NOT NULL CHECK (total_amount > 0),
    estimated_count INTEGER NOT NULL CHECK (estimated_count BETWEEN 0 AND item_count),
    estimated_amount NUMERIC(14,2) NOT NULL CHECK (estimated_amount BETWEEN 0 AND total_amount),
    overdue_count INTEGER NOT NULL CHECK (overdue_count BETWEEN 0 AND item_count),
    CONSTRAINT uq_reminder_summary_slot UNIQUE (space_id, local_date, slot)
);

ALTER TABLE reminder_slot_runs ADD CONSTRAINT fk_reminder_slot_run_summary
    FOREIGN KEY (summary_id) REFERENCES reminder_summaries(id);

-- Every eligible bill in the summary order; the first five are the message details. A bill is either an expense
-- or a recurrence forecast not materialized when the slot was processed (identity recurrence + scheduled date).
CREATE TABLE reminder_summary_items (
    summary_id UUID NOT NULL REFERENCES reminder_summaries(id),
    position INTEGER NOT NULL CHECK (position >= 1),
    expense_id UUID REFERENCES expense_entries(id),
    recurrence_id UUID REFERENCES recurrence_definitions(id),
    scheduled_due_date DATE,
    description VARCHAR(200) NOT NULL,
    amount NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    due_date DATE NOT NULL,
    origin VARCHAR(24) NOT NULL CHECK (origin IN ('ONE_OFF', 'RECURRENCE', 'INSTALLMENT', 'RECURRENCE_FORECAST')),
    estimated BOOLEAN NOT NULL,
    overdue BOOLEAN NOT NULL,
    installment_number INTEGER,
    installment_count INTEGER,
    PRIMARY KEY (summary_id, position),
    CONSTRAINT ck_reminder_item_reference CHECK (
        (expense_id IS NOT NULL AND recurrence_id IS NULL AND scheduled_due_date IS NULL)
        OR (expense_id IS NULL AND recurrence_id IS NOT NULL AND scheduled_due_date IS NOT NULL))
);
CREATE UNIQUE INDEX uq_reminder_item_expense ON reminder_summary_items(summary_id, expense_id)
    WHERE expense_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reminder_item_forecast ON reminder_summary_items(summary_id, recurrence_id, scheduled_due_date)
    WHERE recurrence_id IS NOT NULL;

-- One record per channel of a summary: identity space + date + slot + channel. IN_APP is for both members;
-- WHATSAPP is PLANNED only for a ready channel (recipient = the administrator who consented), else SKIPPED.
CREATE TABLE reminder_summary_channels (
    summary_id UUID NOT NULL REFERENCES reminder_summaries(id),
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('IN_APP', 'WHATSAPP')),
    status VARCHAR(7) NOT NULL CHECK (status IN ('PLANNED', 'SKIPPED')),
    skip_reason VARCHAR(32),
    recipient_user_id UUID REFERENCES identity_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (summary_id, channel),
    CONSTRAINT ck_reminder_channel_reason CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL)),
    CONSTRAINT ck_reminder_channel_recipient CHECK (
        (channel = 'IN_APP' AND recipient_user_id IS NULL)
        OR (channel = 'WHATSAPP' AND ((status = 'PLANNED') = (recipient_user_id IS NOT NULL))))
);
