-- Refund or no-show disputes (Admin Portal Spec, Workflows; D41): Support records what a party
-- told them, then resolves it. One open dispute per booking; resolved ones stay as the record.

CREATE TABLE booking_dispute (
    id               UUID PRIMARY KEY,
    booking_id       UUID        NOT NULL REFERENCES booking (id),
    -- Staff's short note of what they were told. Not clinical; never logged.
    note             TEXT        NOT NULL CHECK (length(note) BETWEEN 1 AND 500),
    opened_by        TEXT        NOT NULL,
    opened_by_name   TEXT        NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL,
    outcome          TEXT CHECK (outcome IN ('SPECIALIST_NO_SHOW', 'SEEKER_NO_SHOW', 'CONNECTION_PROBLEM')),
    refund_amount    BIGINT CHECK (refund_amount >= 0),
    reason           TEXT CHECK (length(reason) <= 500),
    resolved_by      TEXT,
    resolved_by_name TEXT,
    resolved_at      TIMESTAMPTZ,
    CHECK ((outcome IS NULL) = (resolved_at IS NULL))
);

CREATE UNIQUE INDEX booking_dispute_open ON booking_dispute (booking_id) WHERE resolved_at IS NULL;

-- A flag Core raised (neither joined) is resolved the same way; record how.
ALTER TABLE session_flag ADD COLUMN outcome TEXT
    CHECK (outcome IN ('SPECIALIST_NO_SHOW', 'SEEKER_NO_SHOW', 'CONNECTION_PROBLEM'));
