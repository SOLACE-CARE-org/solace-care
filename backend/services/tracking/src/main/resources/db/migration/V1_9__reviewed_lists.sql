-- Reviewed lists (D61): versions of the lists safety or clinical meaning depends on. A clinician
-- drafts; a different one signs off once the list's check passed; then it is in force.
CREATE TABLE reviewed_list_version (
    list_code          TEXT        NOT NULL CHECK (list_code ~ '^[a-z][a-z0-9.-]{2,80}$'),
    version            INTEGER     NOT NULL CHECK (version >= 1),
    status             TEXT        NOT NULL CHECK (status IN ('DRAFT', 'LIVE', 'RETIRED', 'DECLINED')),
    entries            JSONB       NOT NULL,
    note               TEXT        NOT NULL CHECK (length(note) BETWEEN 1 AND 500),
    drafted_by         TEXT        NOT NULL,
    drafted_by_name    TEXT        NOT NULL,
    drafted_at         TIMESTAMPTZ NOT NULL,
    decided_by         TEXT,
    decided_by_name    TEXT,
    decided_at         TIMESTAMPTZ,
    -- What the list's check said at sign-off, e.g. the safety set's recall.
    check_result       TEXT,
    PRIMARY KEY (list_code, version),
    -- The person who drafted a version never decides it.
    CHECK (decided_by IS NULL OR decided_by <> drafted_by)
);
CREATE UNIQUE INDEX reviewed_list_live ON reviewed_list_version (list_code) WHERE status = 'LIVE';
