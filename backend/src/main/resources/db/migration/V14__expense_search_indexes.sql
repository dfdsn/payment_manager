create index ix_expense_entries_space_payment_date
    on expense_entries(space_id, payment_date, created_at, id)
    where payment_date is not null;

create index ix_expense_entries_space_status_reference
    on expense_entries(space_id, status, reference_date, created_at, id);
