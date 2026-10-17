-- Reference data shown before sign-in (System Design, Bilingual design).
--
-- Session languages are data, not an enum (D16): ISO 639 codes, so Pidgin English, Fulfulde and
-- others can be added later without code changes. Names are stored in both app languages.
CREATE TABLE session_language (
    code      TEXT PRIMARY KEY CHECK (code ~ '^[a-z]{2,3}(-[A-Za-z0-9]+)?$'),
    name_en   TEXT    NOT NULL,
    name_fr   TEXT    NOT NULL,
    position  INTEGER NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO session_language (code, name_en, name_fr, position) VALUES
    ('en', 'English', 'Anglais', 1),
    ('fr', 'Français', 'Français', 2);

-- What a seeker can ask for help with (SeekerPreferences screen). Matching later ranks specialists
-- by overlap between these and their focus areas (System Design, Health check, Matching).
CREATE TABLE help_topic (
    code      TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]*$'),
    label_en  TEXT    NOT NULL,
    label_fr  TEXT    NOT NULL,
    position  INTEGER NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO help_topic (code, label_en, label_fr, position) VALUES
    ('anxiety',       'Anxiety',        'Anxiété',            1),
    ('low-mood',      'Low mood',       'Moral bas',          2),
    ('sleep',         'Sleep',          'Sommeil',            3),
    ('relationships', 'Relationships',  'Relations',          4),
    ('grief',         'Grief',          'Deuil',              5),
    ('womens-health', 'Women''s health', 'Santé des femmes',  6),
    ('mens-health',   'Men''s health',  'Santé des hommes',   7),
    ('work-stress',   'Work stress',    'Stress au travail',  8);
