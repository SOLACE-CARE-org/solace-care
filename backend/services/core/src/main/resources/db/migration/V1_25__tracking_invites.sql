-- Tracking to care (Phase 2, epic 6; D52): invites from tracking.recommendation.created, and the
-- invite a booking answers.
ALTER TABLE booking ADD COLUMN recommendation_id UUID;

CREATE TABLE tracking_invite (
    recommendation_id UUID PRIMARY KEY,
    account_id        UUID        NOT NULL,
    rule_code         TEXT        NOT NULL,
    specialty_codes   TEXT[]      NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at        TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ
);
CREATE INDEX tracking_invite_account ON tracking_invite (account_id) WHERE status = 'OPEN';
