-- Phase 4, session scribe (System Design, Recording; D57). Audio is recorded only when both people
-- consented to AI_SCRIBE, neither refused for this session and the sessionScribe flag is on.
CREATE TABLE session_scribe (
    booking_id         UUID PRIMARY KEY,
    seeker_refused     BOOLEAN     NOT NULL DEFAULT false,
    specialist_refused BOOLEAN     NOT NULL DEFAULT false,
    egress_id          TEXT,
    audio_key          TEXT,
    recording          BOOLEAN     NOT NULL DEFAULT false,
    started_at         TIMESTAMPTZ,
    stopped_at         TIMESTAMPTZ,
    -- core.session.ended goes once per booking.
    ended              BOOLEAN     NOT NULL DEFAULT false
);

-- Phase 4, the seeker's safety plan (SafetyPlan.dc.html; D57). Sealed with AES-256-GCM like the
-- health check; only the seeker reads it.
CREATE TABLE safety_plan (
    account_id  UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    sealed      BYTEA       NOT NULL,
    made_with   UUID REFERENCES user_account (id) ON DELETE SET NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- A seeker's answer to the homework companion that the safety monitor flags raises an alert (D57).
ALTER TABLE crisis_alert DROP CONSTRAINT crisis_alert_source_check;
ALTER TABLE crisis_alert ADD CONSTRAINT crisis_alert_source_check
    CHECK (source IN ('REVIEW', 'HEALTH_CHECK', 'COMPANION'));
