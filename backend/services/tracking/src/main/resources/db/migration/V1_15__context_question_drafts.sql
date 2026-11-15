-- Admin-defined context questions (Tracking Service Spec, Context questions; admin screen 34;
-- D61). Questions get the element lifecycle: Operations or a Super admin drafts one, a Clinical
-- reviewer who did not draft it approves it, and publishing puts it in use with no migration and
-- no release. trying_to_conceive and hormonal_treatment v1 stay as the published launch questions.

ALTER TABLE context_question
    ADD COLUMN drafted_by_name  TEXT,
    ADD COLUMN approved_by_name TEXT,
    ADD COLUMN created_at       TIMESTAMPTZ NOT NULL DEFAULT now();

-- At most one draft or version in review per code.
CREATE UNIQUE INDEX context_question_one_open_version ON context_question (code)
    WHERE status IN ('DRAFT', 'IN_REVIEW');

CREATE TABLE context_question_review (
    id            UUID PRIMARY KEY,
    question_id   UUID        NOT NULL REFERENCES context_question (id) ON DELETE CASCADE,
    reviewer_id   TEXT        NOT NULL,
    reviewer_name TEXT        NOT NULL,
    decision      TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'RETURNED')),
    comments      TEXT,
    decided_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX context_question_review_question ON context_question_review (question_id);
