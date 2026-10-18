-- Phase 2, epic 11: the health check (System Design, Health check; D55).

-- A version holds its whole definition: questions (with the locked safety question and screener
-- items), scoring rules, the specialty order that breaks ties, and made-up test sets.
CREATE TABLE hc_version (
    id               UUID PRIMARY KEY,
    major            INTEGER     NOT NULL CHECK (major >= 1),
    minor            INTEGER     NOT NULL CHECK (minor >= 0),
    status           TEXT        NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    questions_json   JSONB       NOT NULL,
    rules_json       JSONB       NOT NULL,
    specialty_order  TEXT[]      NOT NULL DEFAULT '{}',
    test_sets_json   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    -- The version this draft was copied from, to number it: minor, or major when questions or
    -- options were added or removed.
    base_version_id  UUID REFERENCES hc_version (id),
    drafted_by       TEXT        NOT NULL,
    drafted_by_name  TEXT        NOT NULL,
    submitted_at     TIMESTAMPTZ,
    approved_by      TEXT,
    approved_by_name TEXT,
    approved_at      TIMESTAMPTZ,
    published_at     TIMESTAMPTZ,
    retired_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (major, minor)
);
CREATE UNIQUE INDEX hc_version_open ON hc_version ((true)) WHERE status IN ('DRAFT', 'IN_REVIEW');
CREATE UNIQUE INDEX hc_version_published ON hc_version ((true)) WHERE status = 'PUBLISHED';

CREATE TABLE hc_review (
    id            UUID PRIMARY KEY,
    version_id    UUID        NOT NULL REFERENCES hc_version (id),
    reviewer      TEXT        NOT NULL,
    reviewer_name TEXT        NOT NULL,
    decision      TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'RETURNED')),
    comments      TEXT CHECK (length(comments) <= 4000),
    decided_at    TIMESTAMPTZ NOT NULL
);

-- One response. Answers, screener totals and the suggestion are health data: they are kept only
-- in `sealed`, AES-GCM with a key outside the database. A guest's response is found by a hash of
-- its token and is claimed when the guest signs up; unclaimed ones are deleted after [30] days.
CREATE TABLE hc_response (
    id              UUID PRIMARY KEY,
    version_id      UUID        NOT NULL REFERENCES hc_version (id),
    account_id      UUID,
    token_hash      TEXT UNIQUE,
    consent_version TEXT        NOT NULL,
    sealed          BYTEA       NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    safety_stopped  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Preferences filter matches and never score; not health data.
    preferences     JSONB,
    -- Counted for admin screen 28 without opening anyone's answers.
    specialty_code  TEXT,
    callback_phone  TEXT CHECK (callback_phone IS NULL OR callback_phone ~ '^[+][1-9][0-9]{7,14}$'),
    created_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    CHECK (account_id IS NOT NULL OR token_hash IS NOT NULL)
);
CREATE INDEX hc_response_account ON hc_response (account_id, completed_at DESC) WHERE account_id IS NOT NULL;
CREATE INDEX hc_response_unclaimed ON hc_response (created_at) WHERE account_id IS NULL;

-- "Share my answers with the specialist I book", per booking.
CREATE TABLE hc_share (
    booking_id  UUID PRIMARY KEY,
    response_id UUID        NOT NULL REFERENCES hc_response (id) ON DELETE CASCADE,
    shared_at   TIMESTAMPTZ NOT NULL
);

-- The safety answer raises a crisis alert with source HEALTH_CHECK.
ALTER TABLE crisis_alert DROP CONSTRAINT crisis_alert_source_check;
ALTER TABLE crisis_alert ADD CONSTRAINT crisis_alert_source_check
    CHECK (source IN ('REVIEW', 'HEALTH_CHECK'));
