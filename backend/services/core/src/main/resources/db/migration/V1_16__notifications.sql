-- Notifications (Phase 1, epic 9; D38): push devices, each person's settings, and every message sent
-- or waiting. SMS rows never keep their text: guest SMS carry a booking link.

CREATE TABLE push_device (
    token      TEXT        PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    platform   TEXT        NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    time_zone  TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX push_device_account ON push_device (account_id, updated_at DESC);

CREATE TABLE notification_setting (
    account_id        UUID    PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    session_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    discreet          BOOLEAN NOT NULL DEFAULT FALSE,
    -- Null: the defaults in solace.notifications.quiet-hours (D38).
    quiet_start       TIME,
    quiet_end         TIME
);

CREATE TABLE notification (
    id               UUID        PRIMARY KEY,
    -- One of the two.
    account_id       UUID        REFERENCES user_account (id) ON DELETE CASCADE,
    guest_contact_id UUID,
    kind             TEXT        NOT NULL,
    channel          TEXT        NOT NULL CHECK (channel IN ('PUSH', 'SMS', 'EMAIL')),
    booking_id       UUID,
    -- The push text, for the inbox; null for SMS and email.
    text             TEXT,
    status           TEXT        NOT NULL CHECK (status IN ('WAITING', 'SENT', 'FAILED')),
    send_after       TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ,
    read_at          TIMESTAMPTZ,
    -- A message about one booking goes once per person and channel.
    dedupe_key       TEXT        UNIQUE,
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK (account_id IS NOT NULL OR guest_contact_id IS NOT NULL)
);
CREATE INDEX notification_inbox ON notification (account_id, created_at DESC) WHERE channel = 'PUSH';
CREATE INDEX notification_waiting ON notification (send_after) WHERE status = 'WAITING';
