-- The same table each service creates in its own migration (D61).
CREATE TABLE job_trigger (
    name               TEXT PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9.-]{2,80}$'),
    description        TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED')),
    -- Safety or money depends on it: the portal warns before it is paused or slowed.
    caution            BOOLEAN     NOT NULL,
    kind               TEXT        NOT NULL CHECK (kind IN ('CRON', 'INTERVAL')),
    schedule           TEXT        NOT NULL,
    zone               TEXT,
    version            INTEGER     NOT NULL CHECK (version >= 1),
    updated_by         TEXT        NOT NULL,
    updated_by_name    TEXT        NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    registered_version INTEGER,
    last_started_at    TIMESTAMPTZ,
    last_finished_at   TIMESTAMPTZ,
    last_outcome       TEXT CHECK (last_outcome IN ('OK', 'FAILED')),
    next_run_at        TIMESTAMPTZ
);

CREATE TABLE job_trigger_version (
    name            TEXT        NOT NULL REFERENCES job_trigger (name),
    version         INTEGER     NOT NULL,
    status          TEXT        NOT NULL,
    kind            TEXT        NOT NULL,
    schedule        TEXT        NOT NULL,
    zone            TEXT,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (name, version)
);
