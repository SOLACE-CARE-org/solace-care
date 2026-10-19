-- Phase 3, pre-session brief (SessionBrief.dc.html; D56): the specialist's private prep notes for
-- one booking. Only that specialist reads them; every read is audited.
CREATE TABLE session_prep_note (
    booking_id    UUID PRIMARY KEY,
    specialist_id UUID        NOT NULL,
    body          TEXT        NOT NULL CHECK (length(body) <= 4000),
    updated_at    TIMESTAMPTZ NOT NULL
);
