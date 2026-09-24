CREATE TABLE identity_users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    normalized_email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE family_spaces (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    currency_code CHAR(3) NOT NULL CHECK (currency_code = 'BRL'),
    locale VARCHAR(16) NOT NULL CHECK (locale = 'pt-BR'),
    time_zone VARCHAR(64) NOT NULL CHECK (time_zone = 'America/Sao_Paulo'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE space_memberships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_users(id),
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    role VARCHAR(24) NOT NULL CHECK (role IN ('ADMINISTRATOR', 'GUEST')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_active_membership_user
    ON space_memberships(user_id)
    WHERE active;

CREATE UNIQUE INDEX uq_active_administrator_space
    ON space_memberships(space_id)
    WHERE active AND role = 'ADMINISTRATOR';

CREATE TABLE installation_state (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    completed_at TIMESTAMPTZ,
    administrator_user_id UUID UNIQUE REFERENCES identity_users(id),
    space_id UUID UNIQUE REFERENCES family_spaces(id),
    CHECK (
        (completed_at IS NULL AND administrator_user_id IS NULL AND space_id IS NULL)
        OR
        (completed_at IS NOT NULL AND administrator_user_id IS NOT NULL AND space_id IS NOT NULL)
    )
);

INSERT INTO installation_state(singleton_id) VALUES (1);

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(session_id);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session(primary_id) ON DELETE CASCADE
);
