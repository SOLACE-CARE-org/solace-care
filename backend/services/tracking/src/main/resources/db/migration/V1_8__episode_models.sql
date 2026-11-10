-- Phase 2b, epic 2 (Tracking Service Spec, Episode models; D61): episode models replace the fixed
-- cycle model. The algorithm stays in code; each model is a reviewed definition. menstrual_cycle v1
-- counts cycles exactly as the launch cycle model did.
CREATE TABLE episode_model (
    id               UUID        PRIMARY KEY,
    code             TEXT        NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    version          INTEGER     NOT NULL CHECK (version >= 1),
    status           TEXT        NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    start_element    TEXT        NOT NULL,
    end_element      TEXT,
    activity_element TEXT,
    active_values    TEXT[]      NOT NULL DEFAULT '{}',
    -- Days without activity after which the next active day starts a new episode.
    gap_days         INTEGER     NOT NULL CHECK (gap_days BETWEEN 1 AND 365),
    span             TEXT        NOT NULL CHECK (span IN ('UNTIL_NEXT_START', 'UNTIL_END')),
    -- [{"code", "kind": length_days | active_days | days_with_value, "value"?, "labels": {"en", "fr"}}]
    metrics_json     JSONB       NOT NULL,
    labels_json      JSONB       NOT NULL,
    drafted_by       TEXT,
    approved_by      TEXT,
    published_at     TIMESTAMPTZ,
    UNIQUE (code, version),
    CHECK (activity_element IS NOT NULL OR cardinality(active_values) = 0)
);

-- The gap is a placeholder ([10] days in the spec) that clinicians may change in the model.
INSERT INTO episode_model (id, code, version, status, start_element, end_element, activity_element,
                           active_values, gap_days, span, metrics_json, labels_json, published_at) VALUES
('0e1f6a10-0006-4000-8000-000000000001', 'menstrual_cycle', 1, 'PUBLISHED',
 'period_start', 'period_end', 'flow_intensity', '{light,medium,heavy,very_heavy}', 10, 'UNTIL_NEXT_START',
 '[{"code": "length_days", "kind": "length_days", "labels": {"en": "Cycle length", "fr": "Durée du cycle"}},
   {"code": "period_days", "kind": "active_days", "labels": {"en": "Period length", "fr": "Durée des règles"}},
   {"code": "very_heavy_days", "kind": "days_with_value", "value": "very_heavy",
    "labels": {"en": "Very heavy days", "fr": "Jours très abondants"}}]',
 '{"en": "Menstrual cycle", "fr": "Cycle menstruel"}', now());

-- Derived data, rebuilt by the episode reconciler after every change.
CREATE TABLE episode (
    user_id         UUID    NOT NULL,
    model_code      TEXT    NOT NULL,
    start_date      DATE    NOT NULL,
    -- The day before the next episode starts, or the end of the run; null while it is open.
    end_date        DATE,
    active_end_date DATE,
    -- False for the newest episode, which never counts.
    complete        BOOLEAN NOT NULL,
    metrics_json    JSONB   NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (user_id, model_code, start_date)
);

INSERT INTO episode (user_id, model_code, start_date, end_date, active_end_date, complete, metrics_json)
SELECT user_id, 'menstrual_cycle', start_date, end_date, period_end_date, complete,
       jsonb_strip_nulls(jsonb_build_object('length_days', length_days, 'period_days', period_days,
                                            'very_heavy_days', very_heavy_days))
FROM cycle;

DROP TABLE cycle;

-- Signals now come from elements and episode models: flow is read day by day (D54) and by the
-- model; the period events feed the model only.
UPDATE element SET signals = '{daily_value}' WHERE code = 'flow_intensity';
UPDATE element SET signals = '{}' WHERE code IN ('period_start', 'period_end');

-- Rules name episode signals and the generalised operator.
UPDATE rule r SET conditions_json = (
    SELECT jsonb_agg(
               c.cond
               || CASE c.cond ->> 'signal'
                      WHEN 'cycle_length_days' THEN '{"signal": "menstrual_cycle.length_days"}'::jsonb
                      WHEN 'period_days' THEN '{"signal": "menstrual_cycle.period_days"}'::jsonb
                      WHEN 'very_heavy_days' THEN '{"signal": "menstrual_cycle.very_heavy_days"}'::jsonb
                      ELSE '{}'::jsonb
                  END
               || CASE WHEN c.cond ->> 'operator' = 'consecutive_cycles'
                       THEN '{"operator": "consecutive_episodes"}'::jsonb
                       ELSE '{}'::jsonb
                  END
               ORDER BY c.ord)
    FROM jsonb_array_elements(r.conditions_json) WITH ORDINALITY AS c(cond, ord))
WHERE jsonb_typeof(r.conditions_json) = 'array' AND jsonb_array_length(r.conditions_json) > 0;
