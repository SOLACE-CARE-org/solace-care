-- Browser push subscriptions of staff (D38, D47). The endpoint is the push service's address for
-- one browser; p256dh and auth encrypt the message to it (RFC 8291). No personal data is sent.
CREATE TABLE staff_push_subscription (
    endpoint    TEXT PRIMARY KEY CHECK (endpoint ~ '^https://'),
    staff_id    TEXT        NOT NULL,
    p256dh      TEXT        NOT NULL,
    auth        TEXT        NOT NULL,
    language    TEXT        NOT NULL CHECK (language IN ('en', 'fr')),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX staff_push_staff ON staff_push_subscription (staff_id);
