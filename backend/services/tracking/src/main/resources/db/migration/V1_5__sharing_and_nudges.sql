-- Phase 2, epics 7 and 8 (Tracking Service Spec, Principles and Ports; D52).

-- Sharing is the person's call: forward-only (only what is logged after the grant), revocable,
-- and notes only with a separate yes.
CREATE TABLE share_grant (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL,
    specialist_id  UUID        NOT NULL,
    includes_notes BOOLEAN     NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX share_grant_active ON share_grant (user_id, specialist_id) WHERE revoked_at IS NULL;

-- At most one tracking push a day per person (notification-copy.md), as asked of Core.
CREATE TABLE nudge (
    user_id   UUID        NOT NULL,
    local_day DATE        NOT NULL,
    kind      TEXT        NOT NULL CHECK (kind IN ('REMINDER', 'INVITE')),
    sent_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, local_day)
);

-- Nudges pause after the safety monitor flags someone (System Design, ai.risk.flagged).
CREATE TABLE nudge_pause (
    user_id     UUID PRIMARY KEY,
    paused_until TIMESTAMPTZ NOT NULL
);
