-- Content: articles, FAQs, crisis resources and legal text (admin screens 15 and 16; D44).
-- The draft is edited side by side in EN and FR; nothing publishes until both are done, and a
-- crisis resource also needs a clinical reviewer's check after every edit. Published versions
-- are kept as written.

CREATE TABLE content_item (
    id             UUID PRIMARY KEY,
    slug           TEXT        NOT NULL UNIQUE CHECK (slug ~ '^[a-z][a-z0-9-]*$'),
    type           TEXT        NOT NULL CHECK (type IN ('CRISIS_RESOURCE', 'ARTICLE', 'FAQ', 'LEGAL')),
    country        TEXT CHECK (country ~ '^[A-Z]{2}$'),
    title_en       TEXT        NOT NULL DEFAULT '',
    body_en        TEXT        NOT NULL DEFAULT '',
    title_fr       TEXT        NOT NULL DEFAULT '',
    body_fr        TEXT        NOT NULL DEFAULT '',
    -- Crisis resources: the numbers the app dials, [{"service": "police", "number": "117"}].
    numbers        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    en_done        BOOLEAN     NOT NULL DEFAULT FALSE,
    fr_done        BOOLEAN     NOT NULL DEFAULT FALSE,
    clinical       TEXT        NOT NULL CHECK (clinical IN ('NOT_NEEDED', 'NEEDED', 'REQUESTED', 'APPROVED')),
    clinical_by    TEXT,
    clinical_by_name TEXT,
    edited_by      TEXT,
    edited_by_name TEXT,
    edited         BOOLEAN     NOT NULL DEFAULT FALSE,
    version        INTEGER     NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE content_version (
    item_id        UUID        NOT NULL REFERENCES content_item (id),
    version        INTEGER     NOT NULL CHECK (version >= 1),
    title_en       TEXT        NOT NULL,
    body_en        TEXT        NOT NULL,
    title_fr       TEXT        NOT NULL,
    body_fr        TEXT        NOT NULL,
    numbers        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    reason         TEXT,
    published_by_name TEXT     NOT NULL,
    published_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (item_id, version)
);

-- Cameroon's emergency numbers as the app already carries them (D39). The clinical check is still
-- to be done, and the owner confirms the numbers before launch.
INSERT INTO content_item (id, slug, type, country, title_en, body_en, title_fr, body_fr, numbers,
                          en_done, fr_done, clinical, version)
VALUES ('7a3e2c1d-0b4f-4e6a-9c8d-1f2e3d4c5b6a', 'emergency-numbers-cm', 'CRISIS_RESOURCE', 'CM',
        'Emergency numbers, Cameroon',
        E'If you or someone near you is in danger right now, call:\nPolice: 117\nAmbulance and emergency medical help: 119\nFire and rescue: 118\nThese numbers work without data.',
        'Numéros d''urgence, Cameroun',
        E'Si vous ou une personne près de vous êtes en danger maintenant, appelez :\nPolice : 117\nAmbulance et urgences médicales : 119\nPompiers et secours : 118\nCes numéros fonctionnent sans données mobiles.',
        '[{"service": "ambulance", "number": "119"}, {"service": "police", "number": "117"}, {"service": "fire", "number": "118"}]',
        TRUE, TRUE, 'NEEDED', 1);

INSERT INTO content_version (item_id, version, title_en, body_en, title_fr, body_fr, numbers,
                             reason, published_by_name, published_at)
SELECT id, 1, title_en, body_en, title_fr, body_fr, numbers, 'D39 defaults', 'Solace', now()
  FROM content_item WHERE slug = 'emergency-numbers-cm';

-- Legal texts wait for counsel: empty drafts, so the configured v1 stays the version people accept.
INSERT INTO content_item (id, slug, type, title_en, title_fr, clinical) VALUES
    ('0c6f8a2b-3d1e-4f5a-8b7c-2e1d0f9a8b71', 'terms', 'LEGAL', 'Terms of use', 'Conditions d''utilisation', 'NOT_NEEDED'),
    ('1d7a9b3c-4e2f-4a6b-9c8d-3f2e1a0b9c82', 'privacy-notice', 'LEGAL', 'Privacy notice', 'Avis de confidentialité', 'NOT_NEEDED'),
    ('2e8bac4d-5f3a-4b7c-8d9e-4a3f2b1c0d93', 'specialist-terms', 'LEGAL', 'Specialist terms', 'Conditions des spécialistes', 'NOT_NEEDED'),
    ('3f9cbd5e-6a4b-4c8d-9eaf-5b4a3c2d1ea4', 'code-of-conduct', 'LEGAL', 'Code of conduct', 'Code de conduite', 'NOT_NEEDED');
