-- Phase 2b, step 5 (D61; Tracking Service Spec, Testing before publishing): the made-up histories
-- rules are tested against become data. Each is a recipe built back from today, plus the context
-- answers "unless" reads. Staff add more next to the rules they review and never edit them. Real
-- patient data is never used. A version can be submitted only when each rule has a history that
-- fires it and one that does not.

CREATE TABLE synthetic_history (
    id               UUID PRIMARY KEY,
    code             TEXT        NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9_]{1,59}$'),
    -- {"en": "...", "fr": "..."}
    labels_json      JSONB       NOT NULL,
    -- {"parts": [{"pattern": "constant" | "alternating" | "sparse" | "episodes", ...}]}
    recipe_json      JSONB       NOT NULL,
    -- context question answers by code, e.g. {"hormonal_treatment": true}
    context_json     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    seeded           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by       TEXT        NOT NULL,
    created_by_name  TEXT,
    created_at       TIMESTAMPTZ NOT NULL
);

-- The twelve histories the builder used until now, as the code built them: five days into an open
-- cycle, flow logged through each period and three days after it. Sparse logging's sleep is 195
-- minutes instead of 200, since sleep is logged in steps of 15; it still logs too few days to fire.
INSERT INTO synthetic_history (id, code, labels_json, recipe_json, context_json, seeded, created_by, created_at) VALUES
    ('0e1f6a10-0008-4000-8000-000000000001', 'regular_cycles',
     '{"en": "Regular cycles", "fr": "Cycles réguliers"}',
     '{"parts": [{"pattern": "episodes", "start": "period_start", "activity": "flow_intensity", "lengths": [28, 29, 27, 28, 28], "currentDay": 6, "active": [{"value": "medium", "days": 5}], "after": {"value": "none", "days": 3}}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000002', 'long_cycles',
     '{"en": "Long cycles", "fr": "Cycles longs"}',
     '{"parts": [{"pattern": "episodes", "start": "period_start", "activity": "flow_intensity", "lengths": [41, 39, 44, 40], "currentDay": 6, "active": [{"value": "medium", "days": 5}], "after": {"value": "none", "days": 3}}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000003', 'long_cycles_hormonal_treatment',
     '{"en": "Long cycles, hormonal treatment", "fr": "Cycles longs, traitement hormonal"}',
     '{"parts": [{"pattern": "episodes", "start": "period_start", "activity": "flow_intensity", "lengths": [41, 39, 44, 40], "currentDay": 6, "active": [{"value": "medium", "days": 5}], "after": {"value": "none", "days": 3}}]}',
     '{"hormonal_treatment": true}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000004', 'long_periods',
     '{"en": "Long periods", "fr": "Règles longues"}',
     '{"parts": [{"pattern": "episodes", "start": "period_start", "activity": "flow_intensity", "lengths": [30, 31, 29], "currentDay": 6, "active": [{"value": "medium", "days": 9}], "after": {"value": "none", "days": 3}}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000005', 'heavy_flow',
     '{"en": "Heavy flow", "fr": "Flux abondant"}',
     '{"parts": [{"pattern": "episodes", "start": "period_start", "activity": "flow_intensity", "lengths": [28, 30, 29], "currentDay": 6, "active": [{"value": "very_heavy", "days": 4}, {"value": "medium", "days": 2}], "after": {"value": "none", "days": 3}}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000006', 'no_period_90_days',
     '{"en": "No period for 90 days", "fr": "Pas de règles depuis 90 jours"}',
     '{"parts": [{"pattern": "sparse", "element": "flow_intensity", "value": "none", "days": 90, "every": 2}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000007', 'no_period_trying_to_conceive',
     '{"en": "No period, trying to conceive", "fr": "Pas de règles, projet de grossesse"}',
     '{"parts": [{"pattern": "sparse", "element": "flow_intensity", "value": "none", "days": 90, "every": 2}]}',
     '{"trying_to_conceive": true}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000008', 'low_mood_two_weeks',
     '{"en": "Low mood for two weeks", "fr": "Humeur basse pendant deux semaines"}',
     '{"parts": [{"pattern": "alternating", "element": "mood", "values": [2, 2, 2, 3, 2, 2, 2], "days": 14}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000009', 'good_mood',
     '{"en": "Good mood", "fr": "Bonne humeur"}',
     '{"parts": [{"pattern": "constant", "element": "mood", "value": 4, "days": 14}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000010', 'short_sleep',
     '{"en": "Short sleep", "fr": "Sommeil court"}',
     '{"parts": [{"pattern": "constant", "element": "sleep_minutes", "value": 270, "days": 14}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000011', 'enough_sleep',
     '{"en": "Enough sleep", "fr": "Sommeil suffisant"}',
     '{"parts": [{"pattern": "constant", "element": "sleep_minutes", "value": 450, "days": 14}]}',
     '{}', TRUE, 'launch-seed', now()),
    ('0e1f6a10-0008-4000-8000-000000000012', 'sparse_logging',
     '{"en": "Sparse logging", "fr": "Saisie irrégulière"}',
     '{"parts": [{"pattern": "sparse", "element": "mood", "value": 1, "days": 14, "every": 3}, {"pattern": "sparse", "element": "sleep_minutes", "value": 195, "days": 14, "every": 3}]}',
     '{}', TRUE, 'launch-seed', now());
