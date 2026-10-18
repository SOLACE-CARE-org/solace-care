-- Phase 2, epics 1 and 2 (Tracking Service Spec; D49): the element library, observations, notes
-- and the consent state Tracking checks before it processes anything. No table has a foreign key
-- outside Tracking; user ids are Core's pseudonymous account ids from the token.

CREATE TABLE element (
    id               UUID PRIMARY KEY,
    code             TEXT    NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    major            INTEGER NOT NULL CHECK (major >= 1),
    minor            INTEGER NOT NULL DEFAULT 0 CHECK (minor >= 0),
    kind             TEXT    NOT NULL CHECK (kind IN ('DAY_STATE', 'EVENT')),
    value_type       TEXT    NOT NULL CHECK (value_type IN ('INT', 'MINUTES', 'ENUM', 'BOOL', 'DATE', 'TEXT')),
    constraints_json JSONB   NOT NULL DEFAULT '{}'::jsonb,
    input_view       TEXT    NOT NULL,
    icon             TEXT    NOT NULL,
    signals          TEXT[]  NOT NULL DEFAULT '{}',
    labels_json      JSONB   NOT NULL,
    min_app_version  TEXT    NOT NULL,
    UNIQUE (code, major, minor)
);

-- The launch catalogue. Wording from the logging screens (Tracking.dc.html, Checkin.dc.html);
-- each still needs a clinician's wording review (Tracking Service Spec, Launch catalogue).
INSERT INTO element (id, code, major, minor, kind, value_type, constraints_json, input_view, icon, signals, labels_json, min_app_version) VALUES
('0e1f6a10-0001-4000-8000-000000000001', 'mood', 1, 0, 'DAY_STATE', 'INT', '{"min": 1, "max": 5}', 'scale_5', 'mood', '{daily_value}',
 '{"en": {"name": "Mood", "values": {"1": "Awful", "2": "Low", "3": "Okay", "4": "Good", "5": "Great"}},
   "fr": {"name": "Humeur", "values": {"1": "Très mal", "2": "Pas bien", "3": "Moyen", "4": "Bien", "5": "Très bien"}}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000002', 'sleep_minutes', 1, 0, 'DAY_STATE', 'MINUTES', '{"min": 0, "max": 1440, "step": 15}', 'duration_stepper', 'moon', '{daily_value}',
 '{"en": {"name": "Sleep last night"}, "fr": {"name": "Sommeil cette nuit"}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000003', 'note', 1, 0, 'DAY_STATE', 'TEXT', '{"max": 4000}', 'free_text', 'pen', '{}',
 '{"en": {"name": "Note", "help": "Private. A specialist sees notes only if you say yes to sharing them."},
   "fr": {"name": "Note", "help": "Privée. Un spécialiste ne voit vos notes que si vous acceptez de les partager."}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000004', 'flow_intensity', 1, 0, 'DAY_STATE', 'ENUM', '{"values": ["none", "light", "medium", "heavy", "very_heavy"]}', 'flow_calendar', 'droplet', '{cycle_length_days,period_days,very_heavy_days}',
 '{"en": {"name": "Flow", "values": {"none": "None", "light": "Light", "medium": "Medium", "heavy": "Heavy", "very_heavy": "Very heavy"}},
   "fr": {"name": "Flux", "values": {"none": "Aucune", "light": "Léger", "medium": "Moyen", "heavy": "Abondant", "very_heavy": "Très abondant"}}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000005', 'period_start', 1, 0, 'EVENT', 'DATE', '{}', 'event_marker', 'calendar-start', '{cycle_event}',
 '{"en": {"name": "Period started", "help": "Started today"}, "fr": {"name": "Début des règles", "help": "Début aujourd''hui"}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000006', 'period_end', 1, 0, 'EVENT', 'DATE', '{}', 'event_marker', 'calendar-end', '{cycle_event}',
 '{"en": {"name": "Period ended", "help": "Ended today"}, "fr": {"name": "Fin des règles", "help": "Fin aujourd''hui"}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000007', 'acne_severity', 1, 0, 'DAY_STATE', 'ENUM', '{"values": ["none", "mild", "moderate", "severe"]}', 'choice_chips', 'skin', '{daily_value}',
 '{"en": {"name": "Acne", "values": {"none": "None", "mild": "Mild", "moderate": "Moderate", "severe": "Severe"}},
   "fr": {"name": "Acné", "values": {"none": "Aucune", "mild": "Légère", "moderate": "Modérée", "severe": "Sévère"}}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000008', 'excess_hair', 1, 0, 'DAY_STATE', 'BOOL', '{}', 'toggle', 'hair', '{daily_value}',
 '{"en": {"name": "Excess hair growth"}, "fr": {"name": "Pilosité excessive"}}', '1.0.0'),
('0e1f6a10-0001-4000-8000-000000000009', 'stress_level', 1, 0, 'DAY_STATE', 'ENUM', '{"values": ["low", "some", "high"]}', 'choice_chips', 'bolt', '{daily_value}',
 '{"en": {"name": "Stress", "values": {"low": "Low", "some": "Some", "high": "High"}},
   "fr": {"name": "Stress", "values": {"low": "Faible", "some": "Un peu", "high": "Élevé"}}}', '1.0.0');

-- Every insert or tombstone takes the next number, which is the pull cursor.
CREATE SEQUENCE observation_change;

CREATE TABLE observation (
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    client_entry_id  UUID        NOT NULL,
    element_id       UUID        NOT NULL REFERENCES element (id),
    element_code     TEXT        NOT NULL,
    observed_on      DATE        NOT NULL,
    recorded_tz      TEXT        NOT NULL,
    -- One of these, by the element's value type; a note's text lives in entry_note.
    value_int        INTEGER,
    value_text       TEXT,
    value_bool       BOOLEAN,
    value_date       DATE,
    day_state        BOOLEAN     NOT NULL,
    device_id        TEXT        NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL,
    deleted_at       TIMESTAMPTZ,
    change_seq       BIGINT      NOT NULL,
    UNIQUE (user_id, client_entry_id)
);

-- A day state has at most one live value per person, element and local day; a newer push
-- tombstones the older one.
CREATE UNIQUE INDEX observation_live_day_state
    ON observation (user_id, element_code, observed_on)
    WHERE day_state AND deleted_at IS NULL;
CREATE INDEX observation_pull ON observation (user_id, change_seq);
CREATE INDEX observation_signals ON observation (user_id, element_code, observed_on) WHERE deleted_at IS NULL;

-- Notes are storage only: rules never read them, nothing scans them, and deleting the entry
-- deletes the row for real (CLAUDE.md, Privacy and safety).
CREATE TABLE entry_note (
    observation_id UUID PRIMARY KEY REFERENCES observation (id) ON DELETE CASCADE,
    body           TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 4000)
);

-- The person's TRACKING consent as Core last told it (core.consent.changed). No row, no logging.
CREATE TABLE tracking_consent (
    user_id    UUID PRIMARY KEY,
    granted    BOOLEAN     NOT NULL,
    version    TEXT        NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);
