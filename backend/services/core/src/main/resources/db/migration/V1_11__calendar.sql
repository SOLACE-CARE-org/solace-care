-- A specialist's calendar: bookings (epic 5) and time off, in one table so an exclusion constraint
-- makes double booking impossible (System Design, Scheduling). A booking's range includes the
-- break after it.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE calendar_entry (
    id             UUID PRIMARY KEY,
    specialist_id  UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    during         TSTZRANGE   NOT NULL CHECK (NOT isempty(during)),
    kind           TEXT        NOT NULL CHECK (kind IN ('BOOKING', 'TIME_OFF')),
    booking_id     UUID UNIQUE,
    reason         TEXT CHECK (reason IN ('PERSONAL', 'HOLIDAY', 'OTHER')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'BOOKING' AND booking_id IS NOT NULL AND reason IS NULL)
        OR (kind = 'TIME_OFF' AND booking_id IS NULL AND reason IS NOT NULL)),
    CONSTRAINT calendar_entry_no_overlap
        EXCLUDE USING gist (specialist_id WITH =, during WITH &&)
);
