-- H08.3: in-app notifications. One row per summary, member and type: the reminder summary goes to every member
-- active when it is generated (the guest receives reminders only here), and a WhatsApp failure goes to the active
-- administrator only. Read and dismissed are individual per member; nothing here pays, cancels or reschedules a
-- bill. No retention policy is defined yet, so no row is ever deleted by the application.
CREATE TABLE member_notifications (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES family_spaces(id),
    recipient_user_id UUID NOT NULL REFERENCES identity_users(id),
    type VARCHAR(25) NOT NULL CHECK (type IN ('REMINDER_SUMMARY', 'WHATSAPP_DELIVERY_FAILURE')),
    audience VARCHAR(13) NOT NULL CHECK (audience IN ('MEMBER', 'ADMINISTRATOR')),
    summary_id UUID NOT NULL REFERENCES reminder_summaries(id),
    failure_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    CONSTRAINT uq_member_notification UNIQUE (summary_id, recipient_user_id, type),
    CONSTRAINT ck_member_notification_audience CHECK ((type = 'REMINDER_SUMMARY') = (audience = 'MEMBER')),
    CONSTRAINT ck_member_notification_failure CHECK (
        (type = 'WHATSAPP_DELIVERY_FAILURE') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_member_notification_dismissed_read CHECK (dismissed_at IS NULL OR read_at IS NOT NULL)
);

CREATE INDEX ix_member_notifications_inbox
    ON member_notifications(space_id, recipient_user_id, created_at DESC, id DESC);
CREATE INDEX ix_member_notifications_unread
    ON member_notifications(space_id, recipient_user_id) WHERE read_at IS NULL;
