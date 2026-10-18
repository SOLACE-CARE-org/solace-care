-- Phase 2, epic 4 (Tracking Service Spec, Modules and Rules; D51): admin-built modules, their
-- versions, bindings to elements, rules, clinical review, card images and enrolments.

CREATE TABLE image_asset (
    id           UUID PRIMARY KEY,
    object_key   TEXT        NOT NULL,
    content_type TEXT        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    width        INTEGER     NOT NULL,
    height       INTEGER     NOT NULL,
    bytes        INTEGER     NOT NULL CHECK (bytes <= 1048576),
    -- A phone-sized JPEG rendition, when the original could be decoded (JPEG, PNG).
    rendition_key TEXT,
    uploaded_by  TEXT        NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE module (
    id            UUID PRIMARY KEY,
    code          TEXT        NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    accent_colour TEXT        NOT NULL,
    created_by    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE module_version (
    id                      UUID PRIMARY KEY,
    module_id               UUID        NOT NULL REFERENCES module (id),
    major                   INTEGER     NOT NULL CHECK (major >= 1),
    minor                   INTEGER     NOT NULL CHECK (minor >= 0),
    status                  TEXT        NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    name_en                 TEXT        NOT NULL DEFAULT '',
    name_fr                 TEXT        NOT NULL DEFAULT '',
    description_en          TEXT        NOT NULL DEFAULT '',
    description_fr          TEXT        NOT NULL DEFAULT '',
    image_asset_id          UUID REFERENCES image_asset (id),
    image_alt_en            TEXT        NOT NULL DEFAULT '',
    image_alt_fr            TEXT        NOT NULL DEFAULT '',
    min_age                 INTEGER CHECK (min_age IS NULL OR min_age >= 18),
    audience_en             TEXT        NOT NULL DEFAULT '',
    audience_fr             TEXT        NOT NULL DEFAULT '',
    -- {"kind": "YES_NO" | "CHOICE", "question": {"en", "fr"}, "options": [{"code", "en", "fr"}]}
    enrolment_question_json JSONB,
    reminder_default        TIME,
    drafted_by              TEXT        NOT NULL,
    drafted_by_name         TEXT        NOT NULL,
    submitted_by            TEXT,
    submitted_at            TIMESTAMPTZ,
    approved_by             TEXT,
    approved_by_name        TEXT,
    approved_at             TIMESTAMPTZ,
    published_at            TIMESTAMPTZ,
    retired_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    UNIQUE (module_id, major, minor)
);

-- One version being worked on, and one published, per module at a time.
CREATE UNIQUE INDEX module_version_open ON module_version (module_id) WHERE status IN ('DRAFT', 'IN_REVIEW');
CREATE UNIQUE INDEX module_version_published ON module_version (module_id) WHERE status = 'PUBLISHED';

CREATE TABLE module_binding (
    module_version_id UUID    NOT NULL REFERENCES module_version (id) ON DELETE CASCADE,
    element_id        UUID    NOT NULL REFERENCES element (id),
    required          BOOLEAN NOT NULL,
    position          INTEGER NOT NULL,
    PRIMARY KEY (module_version_id, element_id)
);

CREATE TABLE rule (
    id                 UUID PRIMARY KEY,
    module_version_id  UUID    NOT NULL REFERENCES module_version (id) ON DELETE CASCADE,
    code               TEXT    NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'),
    -- [{"signal", "element", "operator", "comparison", "threshold", "count", "window", "minData", "mode"}]
    conditions_json    JSONB   NOT NULL,
    -- [{"field": "pcos_status" | "trying_to_conceive" | "treatments" | "enrolment_answer", "equals"/"includes"}]
    unless_json        JSONB   NOT NULL DEFAULT '[]'::jsonb,
    outcome            TEXT    NOT NULL CHECK (outcome IN ('PATTERN', 'INVITE')),
    specialty_codes    TEXT[]  NOT NULL DEFAULT '{}',
    message_en         TEXT    NOT NULL,
    message_fr         TEXT    NOT NULL,
    cooldown_days      INTEGER NOT NULL CHECK (cooldown_days >= 0),
    repeat_within_days INTEGER CHECK (repeat_within_days IS NULL OR repeat_within_days > 0),
    position           INTEGER NOT NULL,
    UNIQUE (module_version_id, code)
);

CREATE TABLE rule_review (
    id                UUID PRIMARY KEY,
    module_version_id UUID        NOT NULL REFERENCES module_version (id) ON DELETE CASCADE,
    reviewer_id       TEXT        NOT NULL,
    reviewer_name     TEXT        NOT NULL,
    decision          TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'RETURNED')),
    comments          TEXT CHECK (length(comments) <= 4000),
    decided_at        TIMESTAMPTZ NOT NULL
);

CREATE TABLE enrolment (
    user_id           UUID        NOT NULL,
    module_id         UUID        NOT NULL REFERENCES module (id),
    module_version_id UUID        NOT NULL REFERENCES module_version (id),
    enrolled_at       TIMESTAMPTZ NOT NULL,
    left_at           TIMESTAMPTZ,
    reminder_time     TIME,
    -- The answer to the module's enrolment question; it can only switch rules off.
    enrolment_answer  TEXT,
    PRIMARY KEY (user_id, module_id)
);
CREATE INDEX enrolment_active ON enrolment (module_id) WHERE left_at IS NULL;
