-- Phase 3, AI platform (System Design, AI service, Architecture; D56).

-- One row per AI feature: whether it may call a model, and which prompt version is live. A
-- feature can be switched on only while its live version's latest evaluation passed in EN and FR.
CREATE TABLE ai_feature (
    code                TEXT PRIMARY KEY CHECK (code IN ('TRACKING_INSIGHTS', 'PRE_SESSION_BRIEF',
                                                         'REVIEW_MODERATION', 'TRANSLATION')),
    enabled             BOOLEAN     NOT NULL DEFAULT FALSE,
    live_prompt_version TEXT,
    changed_by          TEXT,
    changed_at          TIMESTAMPTZ
);

-- Prompt and policy versions. A draft becomes live only after its evaluation passed and a
-- Clinical reviewer signed off the graded sample.
CREATE TABLE prompt_version (
    id           UUID PRIMARY KEY,
    feature      TEXT        NOT NULL REFERENCES ai_feature (code),
    version      TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('DRAFT', 'LIVE', 'RETIRED')),
    system_text  TEXT        NOT NULL,
    policy_text  TEXT        NOT NULL,
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    signed_off_by TEXT,
    promoted_at  TIMESTAMPTZ,
    UNIQUE (feature, version)
);
CREATE UNIQUE INDEX prompt_version_live ON prompt_version (feature) WHERE status = 'LIVE';

-- Evaluation runs on the bilingual test sets. Failures name the case and the guardrail, never a
-- real person's text: test sets are made up.
CREATE TABLE eval_run (
    id                UUID PRIMARY KEY,
    feature           TEXT        NOT NULL,
    prompt_version_id UUID        REFERENCES prompt_version (id),
    passed_en         INTEGER     NOT NULL,
    total_en          INTEGER     NOT NULL,
    passed_fr         INTEGER     NOT NULL,
    total_fr          INTEGER     NOT NULL,
    gate              NUMERIC(5,2) NOT NULL,
    passed            BOOLEAN     NOT NULL,
    failures_json     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    ran_by            TEXT        NOT NULL,
    ran_at            TIMESTAMPTZ NOT NULL
);
CREATE INDEX eval_run_latest ON eval_run (feature, prompt_version_id, ran_at DESC);

-- Every model call: which feature, route and version, how it went and its size. The texts are
-- kept [30] days for the safety audit, then deleted (System Design, Data rules).
CREATE TABLE ai_call (
    id             UUID PRIMARY KEY,
    feature        TEXT        NOT NULL,
    route          TEXT        NOT NULL,
    prompt_version TEXT,
    subject_id     UUID,
    outcome        TEXT        NOT NULL CHECK (outcome IN ('OK', 'FALLBACK', 'FAILED', 'BLOCKED_GUARDRAIL',
                                                           'BUDGET', 'OFF')),
    tokens_in      INTEGER     NOT NULL DEFAULT 0,
    tokens_out     INTEGER     NOT NULL DEFAULT 0,
    millis         INTEGER     NOT NULL DEFAULT 0,
    prompt_text    TEXT,
    output_text    TEXT,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX ai_call_feature_month ON ai_call (feature, created_at);
CREATE INDEX ai_call_subject ON ai_call (subject_id) WHERE subject_id IS NOT NULL;

-- The seeker's AI_ASSIST and SPECIALIST_SHARING choices, as Core publishes them.
CREATE TABLE ai_consent (
    subject_id UUID        NOT NULL,
    purpose    TEXT        NOT NULL,
    granted    BOOLEAN     NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_id, purpose)
);

INSERT INTO ai_feature (code) VALUES
    ('TRACKING_INSIGHTS'), ('PRE_SESSION_BRIEF'), ('REVIEW_MODERATION'), ('TRANSLATION');
