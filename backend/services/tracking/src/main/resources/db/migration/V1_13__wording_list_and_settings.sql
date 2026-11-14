-- The diagnostic word list and Tracking settings become data (Tracking Service Spec, Tables; D61).

-- What rule messages may not say. Patterns are stored folded (lower case, no accents) and match
-- whole words; a trailing * matches any ending. An ALLOW phrase is taken out of the message before
-- the BLOCK patterns are checked. Clinical reviewers maintain it; the launch list below is the one
-- that was in code (D51), so every launch message passes exactly as before.
CREATE TABLE wording_rule (
    id              UUID PRIMARY KEY,
    language        TEXT        NOT NULL CHECK (language IN ('en', 'fr')),
    pattern         TEXT        NOT NULL CHECK (length(pattern) BETWEEN 2 AND 60),
    kind            TEXT        NOT NULL CHECK (kind IN ('BLOCK', 'ALLOW')),
    created_by      TEXT,
    created_by_name TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (language, pattern, kind)
);

INSERT INTO wording_rule (id, language, pattern, kind) VALUES
(gen_random_uuid(), 'en', 'diagnos*', 'BLOCK'),
(gen_random_uuid(), 'en', 'you have', 'BLOCK'),
(gen_random_uuid(), 'en', 'disorder', 'BLOCK'),
(gen_random_uuid(), 'en', 'disease', 'BLOCK'),
(gen_random_uuid(), 'en', 'syndrome', 'BLOCK'),
(gen_random_uuid(), 'en', 'illness', 'BLOCK'),
(gen_random_uuid(), 'en', 'severe', 'BLOCK'),
(gen_random_uuid(), 'en', 'severity', 'BLOCK'),
(gen_random_uuid(), 'en', 'serious', 'BLOCK'),
(gen_random_uuid(), 'en', 'critical', 'BLOCK'),
(gen_random_uuid(), 'en', 'predict', 'BLOCK'),
(gen_random_uuid(), 'en', 'prediction', 'BLOCK'),
(gen_random_uuid(), 'en', 'forecast', 'BLOCK'),
(gen_random_uuid(), 'en', 'likely to', 'BLOCK'),
(gen_random_uuid(), 'en', 'risk score', 'BLOCK'),
(gen_random_uuid(), 'en', 'triage', 'BLOCK'),
(gen_random_uuid(), 'en', 'depression', 'BLOCK'),
(gen_random_uuid(), 'en', 'depressed', 'BLOCK'),
(gen_random_uuid(), 'en', 'depressive', 'BLOCK'),
(gen_random_uuid(), 'en', 'anxiety disorder', 'BLOCK'),
(gen_random_uuid(), 'en', 'bipolar', 'BLOCK'),
(gen_random_uuid(), 'en', 'pcos', 'BLOCK'),
(gen_random_uuid(), 'en', 'sopk', 'BLOCK'),
(gen_random_uuid(), 'fr', 'diagnos*', 'BLOCK'),
(gen_random_uuid(), 'fr', 'vous avez un', 'BLOCK'),
(gen_random_uuid(), 'fr', 'vous avez une', 'BLOCK'),
(gen_random_uuid(), 'fr', 'vous avez le', 'BLOCK'),
(gen_random_uuid(), 'fr', 'vous avez la', 'BLOCK'),
(gen_random_uuid(), 'fr', 'vous avez des', 'BLOCK'),
(gen_random_uuid(), 'fr', 'trouble', 'BLOCK'),
(gen_random_uuid(), 'fr', 'maladie', 'BLOCK'),
(gen_random_uuid(), 'fr', 'syndrome', 'BLOCK'),
(gen_random_uuid(), 'fr', 'severe', 'BLOCK'),
(gen_random_uuid(), 'fr', 'gravite', 'BLOCK'),
(gen_random_uuid(), 'fr', 'grave', 'BLOCK'),
(gen_random_uuid(), 'fr', 'critique', 'BLOCK'),
(gen_random_uuid(), 'fr', 'predire', 'BLOCK'),
(gen_random_uuid(), 'fr', 'prediction', 'BLOCK'),
(gen_random_uuid(), 'fr', 'prevision', 'BLOCK'),
(gen_random_uuid(), 'fr', 'risque de', 'BLOCK'),
(gen_random_uuid(), 'fr', 'triage', 'BLOCK'),
(gen_random_uuid(), 'fr', 'depressif', 'BLOCK'),
(gen_random_uuid(), 'fr', 'depressive', 'BLOCK'),
(gen_random_uuid(), 'fr', 'bipolaire', 'BLOCK'),
(gen_random_uuid(), 'fr', 'pcos', 'BLOCK'),
(gen_random_uuid(), 'fr', 'sopk', 'BLOCK'),
(gen_random_uuid(), 'en', 'this is not a diagnosis', 'ALLOW'),
(gen_random_uuid(), 'en', 'not a diagnosis', 'ALLOW'),
(gen_random_uuid(), 'fr', 'ce n''est pas un diagnostic', 'ALLOW'),
(gen_random_uuid(), 'fr', 'pas un diagnostic', 'ALLOW');

-- Settings staff change in the portal. Without a row, a setting has its default from config.
CREATE TABLE tracking_setting (
    key             TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_]*$'),
    value           TEXT        NOT NULL,
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
