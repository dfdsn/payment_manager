ALTER TABLE expense_entries
    ADD COLUMN responsible_user_id UUID REFERENCES identity_users(id);

ALTER TABLE expense_correction_events
    ADD COLUMN old_responsible_user_id UUID REFERENCES identity_users(id),
    ADD COLUMN new_responsible_user_id UUID REFERENCES identity_users(id),
    ADD COLUMN old_responsible_name VARCHAR(120),
    ADD COLUMN new_responsible_name VARCHAR(120);

CREATE INDEX ix_expense_entries_responsible
    ON expense_entries(space_id, responsible_user_id)
    WHERE responsible_user_id IS NOT NULL;
