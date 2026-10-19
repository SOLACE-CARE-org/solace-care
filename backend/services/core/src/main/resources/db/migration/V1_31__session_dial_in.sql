-- Phase 4, joining by phone through LiveKit SIP (D59): a PIN per booking, and the media server's
-- dispatch rule that sends a caller with that PIN to the booking's room while joining is open.
CREATE TABLE session_dial_in (
    booking_id UUID PRIMARY KEY,
    pin        TEXT        NOT NULL CHECK (pin ~ '^[0-9]{6}$'),
    rule_id    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX session_dial_in_open_pin ON session_dial_in (pin) WHERE closed_at IS NULL;
