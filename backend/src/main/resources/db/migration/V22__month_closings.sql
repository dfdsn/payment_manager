-- H07.1: symbolic month closing. A closing is one header per space and month; its content is an immutable
-- snapshot kept as numbered versions (H07.1 writes version 1; later versions never replace earlier ones).
-- Snapshots copy labels and values, so a version is never rebuilt from the current expenses.
CREATE TABLE month_closings (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    current_version INTEGER NOT NULL CHECK (current_version >= 1),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_month_closing_space_month UNIQUE (space_id, month),
    CONSTRAINT uq_month_closing_id_space UNIQUE (id, space_id)
);

CREATE TABLE month_closing_versions (
    id UUID PRIMARY KEY,
    closing_id UUID NOT NULL,
    space_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    author_user_id UUID NOT NULL REFERENCES identity_users(id),
    author_display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    business_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    pending_acknowledged BOOLEAN NOT NULL,
    content_digest CHAR(64) NOT NULL,
    planned_count INTEGER NOT NULL CHECK (planned_count >= 0),
    planned_total NUMERIC(18,2) NOT NULL CHECK (planned_total >= 0),
    planned_estimated NUMERIC(18,2) NOT NULL CHECK (planned_estimated >= 0),
    paid_count INTEGER NOT NULL CHECK (paid_count >= 0),
    paid_total NUMERIC(18,2) NOT NULL CHECK (paid_total >= 0),
    pending_count INTEGER NOT NULL CHECK (pending_count >= 0),
    pending_total NUMERIC(18,2) NOT NULL CHECK (pending_total >= 0),
    pending_estimated NUMERIC(18,2) NOT NULL CHECK (pending_estimated >= 0),
    overdue_count INTEGER NOT NULL CHECK (overdue_count >= 0),
    overdue_total NUMERIC(18,2) NOT NULL CHECK (overdue_total >= 0),
    overdue_estimated NUMERIC(18,2) NOT NULL CHECK (overdue_estimated >= 0),
    adjustment_increase NUMERIC(18,2) NOT NULL CHECK (adjustment_increase >= 0),
    adjustment_discount NUMERIC(18,2) NOT NULL CHECK (adjustment_discount >= 0),
    CONSTRAINT fk_month_closing_version_closing FOREIGN KEY (closing_id, space_id)
        REFERENCES month_closings(id, space_id),
    CONSTRAINT uq_month_closing_version_number UNIQUE (closing_id, version_number),
    CONSTRAINT ck_month_closing_counts CHECK (planned_count = paid_count + pending_count
        AND overdue_count <= pending_count),
    -- Closing with pending entries requires the member to have acknowledged the warning (RF-FEC-02).
    CONSTRAINT ck_month_closing_pending_acknowledged CHECK (pending_count = 0 OR pending_acknowledged)
);
CREATE INDEX ix_month_closing_versions_space ON month_closing_versions(space_id, month, version_number);

CREATE TABLE month_closing_categories (
    version_id UUID NOT NULL REFERENCES month_closing_versions(id),
    position SMALLINT NOT NULL CHECK (position >= 0),
    category_id UUID,
    category_name VARCHAR(60),
    entry_count INTEGER NOT NULL CHECK (entry_count > 0),
    planned_total NUMERIC(18,2) NOT NULL CHECK (planned_total >= 0),
    planned_estimated NUMERIC(18,2) NOT NULL CHECK (planned_estimated >= 0),
    paid_total NUMERIC(18,2) NOT NULL CHECK (paid_total >= 0),
    pending_count INTEGER NOT NULL CHECK (pending_count >= 0),
    pending_total NUMERIC(18,2) NOT NULL CHECK (pending_total >= 0),
    PRIMARY KEY (version_id, position),
    CHECK ((category_id IS NULL) = (category_name IS NULL))
);

CREATE TABLE month_closing_lines (
    version_id UUID NOT NULL REFERENCES month_closing_versions(id),
    expense_id UUID NOT NULL,
    description VARCHAR(200) NOT NULL,
    origin VARCHAR(24) NOT NULL CHECK (origin IN ('ONE_OFF', 'RECURRENCE', 'INSTALLMENT')),
    installment_number SMALLINT,
    installment_count SMALLINT,
    reference_date DATE NOT NULL,
    due_date_informed BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PAID')),
    charge_amount NUMERIC(10,2) NOT NULL CHECK (charge_amount > 0),
    charge_confirmed BOOLEAN NOT NULL,
    paid_amount NUMERIC(10,2) CHECK (paid_amount > 0),
    overdue BOOLEAN NOT NULL,
    category_id UUID,
    category_name VARCHAR(60),
    PRIMARY KEY (version_id, expense_id),
    CHECK ((status = 'PAID') = (paid_amount IS NOT NULL)),
    CHECK (status = 'PENDING' OR NOT overdue),
    CHECK ((installment_number IS NULL) = (installment_count IS NULL))
);

CREATE TABLE month_closing_events (
    id UUID PRIMARY KEY,
    closing_id UUID NOT NULL REFERENCES month_closings(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'MONTH_CLOSED'),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (closing_id, version_number)
);

CREATE TABLE month_closing_requests (
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    operation VARCHAR(32) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    version_id UUID REFERENCES month_closing_versions(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, actor_user_id, operation, idempotency_key),
    CHECK ((version_id IS NULL AND completed_at IS NULL) OR (version_id IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_month_closing_requests_created_at ON month_closing_requests(created_at);

-- A saved snapshot and its audit never change: corrections produce new versions, not edits.
CREATE FUNCTION reject_month_closing_snapshot_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'month closing snapshots are immutable (%)', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_month_closing_versions_immutable BEFORE UPDATE OR DELETE ON month_closing_versions
    FOR EACH ROW EXECUTE FUNCTION reject_month_closing_snapshot_change();
CREATE TRIGGER tg_month_closing_categories_immutable BEFORE UPDATE OR DELETE ON month_closing_categories
    FOR EACH ROW EXECUTE FUNCTION reject_month_closing_snapshot_change();
CREATE TRIGGER tg_month_closing_lines_immutable BEFORE UPDATE OR DELETE ON month_closing_lines
    FOR EACH ROW EXECUTE FUNCTION reject_month_closing_snapshot_change();
CREATE TRIGGER tg_month_closing_events_immutable BEFORE UPDATE OR DELETE ON month_closing_events
    FOR EACH ROW EXECUTE FUNCTION reject_month_closing_snapshot_change();
