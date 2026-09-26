CREATE TABLE expense_categories (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    name VARCHAR(60) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    normalized_name VARCHAR(60) NOT NULL,
    archived_at TIMESTAMPTZ,
    created_by_user_id UUID REFERENCES identity_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID REFERENCES identity_users(id),
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (space_id, normalized_name)
);

CREATE TABLE expense_category_events (
    id BIGSERIAL PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES expense_categories(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('CREATED', 'RENAMED', 'ARCHIVED')),
    actor_user_id UUID NOT NULL REFERENCES identity_users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    category_version BIGINT NOT NULL,
    previous_name VARCHAR(60),
    current_name VARCHAR(60) NOT NULL
);

ALTER TABLE expense_entries ADD COLUMN category_id UUID REFERENCES expense_categories(id);
ALTER TABLE expense_correction_events
    ADD COLUMN old_category_id UUID REFERENCES expense_categories(id),
    ADD COLUMN new_category_id UUID REFERENCES expense_categories(id),
    ADD COLUMN old_category_name VARCHAR(60),
    ADD COLUMN new_category_name VARCHAR(60);
CREATE INDEX ix_expense_entries_category ON expense_entries(space_id, category_id);

INSERT INTO expense_categories(id, space_id, name, normalized_name, created_at, updated_at)
SELECT md5(s.id::text || ':' || seed.name)::uuid, s.id, seed.name, lower(seed.name), s.created_at, s.created_at
FROM family_spaces s
CROSS JOIN (VALUES ('Moradia'), ('Alimentação'), ('Transporte'), ('Saúde'), ('Educação'), ('Lazer'), ('Outros')) seed(name);

CREATE FUNCTION seed_initial_expense_categories() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO expense_categories(id, space_id, name, normalized_name, created_at, updated_at)
    SELECT md5(NEW.id::text || ':' || seed.name)::uuid, NEW.id, seed.name, lower(seed.name), NEW.created_at, NEW.created_at
    FROM (VALUES ('Moradia'), ('Alimentação'), ('Transporte'), ('Saúde'), ('Educação'), ('Lazer'), ('Outros')) seed(name);
    RETURN NEW;
END $$;

CREATE TRIGGER trg_seed_initial_expense_categories
AFTER INSERT ON family_spaces FOR EACH ROW EXECUTE FUNCTION seed_initial_expense_categories();
