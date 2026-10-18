-- Sessions (Phase 1, epic 7; D18, D36): attendance from the media server's webhooks, and bookings
-- Support has to look at. No names, no media: who joined (by role) and when.

CREATE TABLE session_event (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- LiveKit's event id: webhooks are retried, each counts once.
    event_id   TEXT        NOT NULL UNIQUE,
    booking_id UUID        NOT NULL,
    role       TEXT        CHECK (role IN ('SEEKER', 'SPECIALIST')),
    kind       TEXT        NOT NULL CHECK (kind IN ('JOINED', 'LEFT', 'ROOM_FINISHED')),
    at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX session_event_booking ON session_event (booking_id, at);

-- Bookings attendance cannot settle on its own, e.g. neither person joined (D36).
CREATE TABLE session_flag (
    booking_id  UUID        PRIMARY KEY,
    reason      TEXT        NOT NULL CHECK (reason IN ('BOTH_ABSENT')),
    created_at  TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);
