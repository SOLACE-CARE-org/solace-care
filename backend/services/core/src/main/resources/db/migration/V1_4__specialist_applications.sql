-- Specialist sign-up and the application reviewers check (Phase 1, epic 2).

-- A specialist agrees to the Specialist terms and the Code of conduct, not the seekers' privacy
-- notice, so the account no longer requires that version.
ALTER TABLE user_account ALTER COLUMN privacy_notice_version DROP NOT NULL;

-- Professions a specialist can apply as (SpecProfessional). Data, not an enum (D26). The two
-- physical-health professions wait, disabled, for the open question on physical-health
-- specialists.
CREATE TABLE profession (
    code      TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]*$'),
    label_en  TEXT    NOT NULL,
    label_fr  TEXT    NOT NULL,
    position  INTEGER NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO profession (code, label_en, label_fr, position, enabled) VALUES
    ('clinical-psychologist', 'Clinical psychologist', 'Psychologue clinicienne',     1, TRUE),
    ('psychiatrist',          'Psychiatrist',          'Psychiatre',                  2, TRUE),
    ('licensed-counsellor',   'Licensed counsellor',   'Conseiller agréé',            3, TRUE),
    ('gynaecologist',         'Gynaecologist',         'Gynécologue',                 4, FALSE),
    ('endocrinologist',       'Endocrinologist',       'Endocrinologue',              5, FALSE);

-- System Design, Core data model: specialist_profile. One row per specialist account; its status
-- is the onboarding state machine (DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED/REJECTED →
-- SUSPENDED). Practice fields are null until step 2 is saved.
CREATE TABLE specialist_profile (
    account_id               UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    full_name                TEXT        NOT NULL CHECK (length(full_name) BETWEEN 1 AND 120),
    code_of_conduct_version  TEXT        NOT NULL,
    verification_status      TEXT        NOT NULL DEFAULT 'DRAFT'
                             CHECK (verification_status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW',
                                                            'APPROVED', 'REJECTED', 'SUSPENDED')),
    profession_code          TEXT REFERENCES profession (code),
    licence_number           TEXT CHECK (length(licence_number) BETWEEN 1 AND 60),
    years_experience         INTEGER CHECK (years_experience BETWEEN 0 AND 70),
    licensing_body           TEXT CHECK (length(licensing_body) BETWEEN 1 AND 160),
    -- The bio in the language it was written in; the other one is drafted later and checked by the
    -- specialist before it goes live.
    bio_en                   TEXT CHECK (length(bio_en) <= 600),
    bio_fr                   TEXT CHECK (length(bio_fr) <= 600),
    bio_language             TEXT CHECK (bio_language IN ('en', 'fr')),
    practice_saved_at        TIMESTAMPTZ,
    languages_saved_at       TIMESTAMPTZ,
    services_saved_at        TIMESTAMPTZ,
    submitted_at             TIMESTAMPTZ,
    -- Set by staff (D25).
    interview_at             TIMESTAMPTZ,
    -- The reviewer's reason for a change request or a rejection, shown to the applicant.
    review_note              TEXT CHECK (length(review_note) <= 1000),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX specialist_profile_status_idx ON specialist_profile (verification_status, submitted_at);

-- Areas the specialist works with: help topics, so matching can compare them with a seeker's (D26).
CREATE TABLE specialist_focus_area (
    account_id  UUID NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    topic_code  TEXT NOT NULL REFERENCES help_topic (code),
    PRIMARY KEY (account_id, topic_code)
);

-- System Design, Core data model: specialist_language. Declared at sign-up; a reviewer confirms
-- each level in the onboarding interview. Only PROFESSIONAL and above are bookable.
CREATE TABLE specialist_language (
    account_id     UUID NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    language_code  TEXT NOT NULL REFERENCES session_language (code),
    proficiency    TEXT NOT NULL CHECK (proficiency IN ('NATIVE', 'FLUENT', 'PROFESSIONAL')),
    verified_at    TIMESTAMPTZ,
    PRIMARY KEY (account_id, language_code)
);

-- System Design, Core data model: service_offering. One per modality for now; specialty_id comes
-- with the specialty taxonomy (epic 3). Prices are whole FCFA.
CREATE TABLE service_offering (
    id             UUID PRIMARY KEY,
    specialist_id  UUID    NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    modality       TEXT    NOT NULL CHECK (modality IN ('AUDIO', 'VIDEO')),
    enabled        BOOLEAN NOT NULL,
    duration_min   INTEGER NOT NULL CHECK (duration_min > 0),
    price          BIGINT  NOT NULL CHECK (price >= 0),
    currency       TEXT    NOT NULL DEFAULT 'XAF' CHECK (currency = 'XAF'),
    UNIQUE (specialist_id, modality)
);

-- Where earnings are paid (SpecPayout).
CREATE TABLE specialist_payout_details (
    account_id   UUID PRIMARY KEY REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    method       TEXT NOT NULL CHECK (method IN ('MTN_MOMO', 'ORANGE_MONEY', 'BANK_TRANSFER')),
    phone        TEXT CHECK (phone ~ '^[+][1-9][0-9]{7,14}$'),
    holder_name  TEXT NOT NULL CHECK (length(holder_name) BETWEEN 1 AND 120),
    saved_at     TIMESTAMPTZ NOT NULL,
    CHECK (method = 'BANK_TRANSFER' OR phone IS NOT NULL)
);
