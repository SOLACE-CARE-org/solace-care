-- Admin-defined episode models (Tracking Service Spec, Episode models; admin screen 33; D61).
-- Models get the element lifecycle: Operations or a Super admin drafts one from published
-- elements, a Clinical reviewer who did not draft it approves it, and publishing puts it in use
-- with no migration and no release. menstrual_cycle v1 stays as the published launch model.

ALTER TABLE episode_model
    ADD COLUMN drafted_by_name  TEXT,
    ADD COLUMN approved_by_name TEXT,
    ADD COLUMN created_at       TIMESTAMPTZ NOT NULL DEFAULT now();

-- At most one draft or version in review per code.
CREATE UNIQUE INDEX episode_model_one_open_version ON episode_model (code)
    WHERE status IN ('DRAFT', 'IN_REVIEW');

CREATE TABLE episode_model_review (
    id            UUID PRIMARY KEY,
    model_id      UUID        NOT NULL REFERENCES episode_model (id) ON DELETE CASCADE,
    reviewer_id   TEXT        NOT NULL,
    reviewer_name TEXT        NOT NULL,
    decision      TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'RETURNED')),
    comments      TEXT,
    decided_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX episode_model_review_model ON episode_model_review (model_id);
