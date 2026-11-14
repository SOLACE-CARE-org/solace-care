-- Admin-defined elements (Tracking Service Spec, Elements and Input views; D61).
-- Elements now have the module lifecycle: admins draft them from the input views the app ships,
-- a clinical reviewer who did not draft one approves it, and only then can a module bind it.
-- The launch catalogue stays as published v1 elements.

-- The input views the app ships. Written by migrations only: a new view is app code.
-- props_schema_json: each prop the view takes, {"type": "integer"|"string"|"boolean"} with
-- "minimum"/"maximum" for an integer.
CREATE TABLE input_view (
    key               TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_]*$'),
    value_types       TEXT[]  NOT NULL,
    kinds             TEXT[]  NOT NULL,
    props_schema_json JSONB   NOT NULL DEFAULT '{}'::jsonb,
    min_app_version   TEXT    NOT NULL,
    position          INTEGER NOT NULL UNIQUE
);

INSERT INTO input_view (key, value_types, kinds, props_schema_json, min_app_version, position) VALUES
('scale_5',          '{INT}',     '{DAY_STATE}', '{}', '1.0.0', 1),
('duration_stepper', '{MINUTES}', '{DAY_STATE}', '{"start": {"type": "integer", "minimum": 0, "maximum": 1440}}', '1.0.0', 2),
('choice_chips',     '{ENUM}',    '{DAY_STATE}', '{}', '1.0.0', 3),
('toggle',           '{BOOL}',    '{DAY_STATE}', '{}', '1.0.0', 4),
('flow_calendar',    '{ENUM}',    '{DAY_STATE}', '{}', '1.0.0', 5),
('event_marker',     '{DATE}',    '{EVENT}',     '{}', '1.0.0', 6),
('free_text',        '{TEXT}',    '{DAY_STATE}', '{}', '1.0.0', 7);

ALTER TABLE element
    ADD COLUMN status           TEXT        NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    ADD COLUMN view_props_json  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN drafted_by       TEXT,
    ADD COLUMN drafted_by_name  TEXT,
    ADD COLUMN approved_by      TEXT,
    ADD COLUMN approved_by_name TEXT,
    ADD COLUMN published_at     TIMESTAMPTZ,
    ADD COLUMN created_at       TIMESTAMPTZ NOT NULL DEFAULT now();

-- The stepper opened at 7 hours in app code; it is now the view's prop.
UPDATE element SET view_props_json = '{"start": 420}' WHERE code = 'sleep_minutes';
UPDATE element SET published_at = created_at WHERE status = 'PUBLISHED';

-- At most one draft or version in review per code.
CREATE UNIQUE INDEX element_one_open_version ON element (code) WHERE status IN ('DRAFT', 'IN_REVIEW');

CREATE TABLE element_review (
    id            UUID PRIMARY KEY,
    element_id    UUID        NOT NULL REFERENCES element (id) ON DELETE CASCADE,
    reviewer_id   TEXT        NOT NULL,
    reviewer_name TEXT        NOT NULL,
    decision      TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'RETURNED')),
    comments      TEXT,
    decided_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX element_review_element ON element_review (element_id);
