-- The safety monitor's decisions (Phase 1, epic 10; D39). Never the text that was checked: only what
-- was checked, the verdict, and which classifier and policy decided (AI-9).
CREATE TABLE safety_decision (
    id                 UUID        PRIMARY KEY,
    source             TEXT        NOT NULL CHECK (source IN ('REVIEW')),
    source_id          UUID        NOT NULL,
    subject_id         UUID        NOT NULL,
    flagged            BOOLEAN     NOT NULL,
    category           TEXT        CHECK (category IN ('SUICIDAL_IDEATION', 'SELF_HARM', 'ACUTE_ANXIETY',
                                                       'VIOLENT_THOUGHTS', 'SUBSTANCE_CRISIS', 'RISK_TAKING')),
    classifier_version TEXT        NOT NULL,
    policy_version     TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    CHECK (flagged = (category IS NOT NULL))
);
CREATE INDEX safety_decision_source ON safety_decision (source, source_id);
