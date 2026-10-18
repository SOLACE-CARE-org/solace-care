-- The crisis queue and the on-call rota (Phase 1, epic 10; D11, D15, D39). An alert keeps ids, the
-- category and who did what; the flagged text stays with its source and is read, audited, on view.

CREATE TABLE crisis_alert (
    id                    UUID        PRIMARY KEY,
    -- One alert per safety decision: ai.risk.flagged may arrive twice.
    decision_id           UUID        NOT NULL UNIQUE,
    source                TEXT        NOT NULL CHECK (source IN ('REVIEW')),
    source_id             UUID        NOT NULL,
    subject_id            UUID        NOT NULL,
    guest                 BOOLEAN     NOT NULL,
    category              TEXT        NOT NULL,
    language              TEXT,
    status                TEXT        NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'CLOSED')),
    raised_at             TIMESTAMPTZ NOT NULL,
    escalated_backup_at   TIMESTAMPTZ,
    escalated_admins_at   TIMESTAMPTZ,
    acknowledged_by       TEXT,
    acknowledged_by_name  TEXT,
    acknowledged_at       TIMESTAMPTZ,
    outcome               TEXT CHECK (outcome IN ('SAFE_FOR_NOW', 'REFERRED_TO_EMERGENCY_SERVICES',
                                                  'NOT_REACHED_FOLLOW_UP', 'NOT_A_CRISIS')),
    note                  TEXT CHECK (length(note) <= 2000),
    closed_by             TEXT,
    closed_at             TIMESTAMPTZ
);
CREATE INDEX crisis_alert_open ON crisis_alert (raised_at) WHERE status <> 'CLOSED';

-- Who is on call, until staffing is decided (D39). Work phones for crisis SMS only (D15).
CREATE TABLE crisis_rota (
    id        UUID        PRIMARY KEY,
    staff_id  TEXT        NOT NULL,
    name      TEXT        NOT NULL,
    phone     TEXT        NOT NULL CHECK (phone ~ '^[+][1-9][0-9]{7,14}$'),
    language  TEXT        NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'fr')),
    role      TEXT        NOT NULL CHECK (role IN ('PRIMARY', 'BACKUP', 'ESCALATION')),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at   TIMESTAMPTZ NOT NULL,
    CHECK (starts_at < ends_at)
);
CREATE INDEX crisis_rota_when ON crisis_rota (role, starts_at, ends_at);
