-- Ratings and reviews (Phase 1, epic 8; D37). One per completed booking. The seeker is kept only as
-- the pseudonymous subject id; the specialist and the public never see who wrote it.

CREATE TABLE rating (
    id                UUID        PRIMARY KEY,
    booking_id        UUID        NOT NULL UNIQUE,
    specialist_id     UUID        NOT NULL,
    subject_id        UUID        NOT NULL,
    guest             BOOLEAN     NOT NULL,
    session_at        TIMESTAMPTZ NOT NULL,
    session_language  TEXT        NOT NULL,
    stars             SMALLINT    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    tags              TEXT[]      NOT NULL DEFAULT '{}',
    -- The written review, as the seeker wrote it; what the profile shows is published_text.
    text              TEXT        CHECK (length(text) <= 1000),
    -- For the Solace team only (D37).
    private_note      TEXT        CHECK (length(private_note) <= 1000),
    flags             TEXT[]      NOT NULL DEFAULT '{}',
    -- NONE: stars only, nothing to moderate.
    status            TEXT        NOT NULL CHECK (status IN ('NONE', 'PENDING', 'PUBLISHED', 'HIDDEN', 'FLAGGED')),
    -- The safety monitor's verdict (epic 10).
    safety            TEXT        NOT NULL DEFAULT 'NOT_CHECKED'
                                  CHECK (safety IN ('NOT_CHECKED', 'CLEAR', 'FLAGGED')),
    published_text    TEXT,
    hidden_reason     TEXT,
    moderated_by      TEXT,
    moderated_by_name TEXT,
    moderated_at      TIMESTAMPTZ,
    reply             TEXT        CHECK (length(reply) <= 600),
    replied_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX rating_specialist ON rating (specialist_id, status, session_at DESC);
CREATE INDEX rating_queue ON rating (created_at) WHERE status = 'PENDING';
