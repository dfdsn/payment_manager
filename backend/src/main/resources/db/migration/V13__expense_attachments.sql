create table expense_attachments (
    id uuid primary key,
    expense_id uuid not null references expense_entries(id),
    space_id uuid not null references family_spaces(id),
    storage_key uuid not null unique,
    original_name varchar(255) not null,
    media_type varchar(40) not null check (media_type in ('application/pdf','image/jpeg','image/png')),
    size_bytes bigint not null check (size_bytes between 1 and 10485760),
    status varchar(16) not null check (status in ('STAGED','AVAILABLE','REMOVED')),
    uploaded_by_user_id uuid not null references identity_users(id),
    uploaded_at timestamptz not null,
    removed_by_user_id uuid references identity_users(id),
    removed_at timestamptz,
    constraint expense_attachment_removal_consistent check (
      (status <> 'REMOVED' and removed_by_user_id is null and removed_at is null) or
      (status = 'REMOVED' and removed_by_user_id is not null and removed_at is not null)
    )
);
create index ix_expense_attachments_active on expense_attachments(expense_id, status);

create table expense_attachment_requests (
    space_id uuid not null references family_spaces(id),
    actor_user_id uuid not null references identity_users(id),
    idempotency_key uuid not null,
    request_hash varchar(64) not null,
    attachment_id uuid references expense_attachments(id),
    created_at timestamptz not null,
    primary key(space_id, actor_user_id, idempotency_key)
);

create table expense_attachment_audit (
    id uuid primary key,
    attachment_id uuid not null references expense_attachments(id),
    expense_id uuid not null references expense_entries(id),
    space_id uuid not null references family_spaces(id),
    actor_user_id uuid not null references identity_users(id),
    event_type varchar(24) not null check (event_type in ('ATTACHMENT_ADDED','ATTACHMENT_REMOVED')),
    occurred_at timestamptz not null
);
