-- Phase 4: the session scribe, homework and the companion (System Design, AI features 4 and 5; D57).

ALTER TABLE ai_feature DROP CONSTRAINT ai_feature_code_check;
ALTER TABLE ai_feature ADD CONSTRAINT ai_feature_code_check
    CHECK (code IN ('TRACKING_INSIGHTS', 'PRE_SESSION_BRIEF', 'REVIEW_MODERATION', 'TRANSLATION',
                    'SESSION_SCRIBE', 'COMPANION', 'ADMIN_COPILOT'));
INSERT INTO ai_feature (code) VALUES ('SESSION_SCRIBE'), ('COMPANION'), ('ADMIN_COPILOT');

-- A seeker's companion answer is checked like a review.
ALTER TABLE safety_decision DROP CONSTRAINT safety_decision_source_check;
ALTER TABLE safety_decision ADD CONSTRAINT safety_decision_source_check
    CHECK (source IN ('REVIEW', 'COMPANION'));

-- Every session that ended, from core.session.ended: ids only. Homework is set against these.
CREATE TABLE ended_session (
    booking_id        UUID PRIMARY KEY,
    specialist_id     UUID        NOT NULL,
    seeker_subject_id UUID        NOT NULL,
    guest             BOOLEAN     NOT NULL,
    session_language  TEXT        NOT NULL,
    ended_at          TIMESTAMPTZ NOT NULL
);

-- Session audio waiting to be transcribed or deleted. Deleted once the note is signed or
-- discarded, after 7 days at most (System Design, Recording); the bucket also expires it.
CREATE TABLE scribe_audio (
    booking_id  UUID PRIMARY KEY,
    audio_key   TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    deleted_at  TIMESTAMPTZ
);

-- One note per session. Sections are sealed (AES-256-GCM). A draft not signed within 7 days is
-- deleted with its transcript and the audio; a signed note stays with its specialist.
CREATE TABLE session_note (
    id            UUID PRIMARY KEY,
    booking_id    UUID        NOT NULL UNIQUE,
    specialist_id UUID        NOT NULL,
    seeker_subject_id UUID    NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('DRAFT', 'SIGNED')),
    format        TEXT        NOT NULL CHECK (format IN ('SOAP', 'DAP')),
    sealed        BYTEA       NOT NULL,
    ai_drafted    BOOLEAN     NOT NULL,
    risk_signals  BOOLEAN     NOT NULL DEFAULT false,
    prompt_version TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    signed_at     TIMESTAMPTZ,
    delete_at     TIMESTAMPTZ
);
CREATE INDEX session_note_specialist ON session_note (specialist_id, created_at DESC);

-- The transcript, sealed, kept only so the other format can be drafted; deleted at signing.
CREATE TABLE note_transcript (
    note_id    UUID PRIMARY KEY REFERENCES session_note (id) ON DELETE CASCADE,
    sealed     BYTEA       NOT NULL,
    language   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Homework a specialist sets after a session (AssignHomework.dc.html).
CREATE TABLE homework (
    id                UUID PRIMARY KEY,
    booking_id        UUID        NOT NULL,
    specialist_id     UUID        NOT NULL,
    seeker_subject_id UUID        NOT NULL,
    exercise          TEXT        NOT NULL CHECK (exercise IN ('THOUGHT_RECORD', 'SLEEP_DIARY',
                                                               'WORRY_TIME', 'CUSTOM')),
    custom_task       TEXT,
    frequency         TEXT        NOT NULL CHECK (frequency IN ('TIMES', 'DAILY', 'ONCE')),
    target            INTEGER     NOT NULL CHECK (target BETWEEN 1 AND 60),
    note              TEXT,
    companion_guided  BOOLEAN     NOT NULL,
    due_by            TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX homework_seeker ON homework (seeker_subject_id, due_by DESC);

-- The seeker's records for a homework, sealed; theirs only. The specialist sees the count.
CREATE TABLE homework_entry (
    id           UUID PRIMARY KEY,
    homework_id  UUID        NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    sealed       BYTEA       NOT NULL,
    step         TEXT        NOT NULL,
    completed    BOOLEAN     NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE INDEX homework_entry_open ON homework_entry (homework_id, completed);

-- The companion's vetted corpus (System Design, Retrieval): bilingual passages only, no personal
-- data. Embeddings are filled when an embedding model is configured; until then words are matched.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE companion_passage (
    id         UUID PRIMARY KEY,
    exercise   TEXT        NOT NULL,
    locale     TEXT        NOT NULL CHECK (locale IN ('en', 'fr')),
    text       TEXT        NOT NULL,
    source     TEXT        NOT NULL,
    embedding  vector,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX companion_passage_exercise ON companion_passage (exercise, locale);

INSERT INTO companion_passage (id, exercise, locale, text, source) VALUES
  ('6f1c1a50-0001-4000-8000-000000000001', 'THOUGHT_RECORD', 'en',
   'A thought record slows a hard moment down: what happened, the thought that came, the feeling it brought, and a kinder way to see it. Noticing the thought is already a step.',
   'design:AssignHomework'),
  ('6f1c1a50-0001-4000-8000-000000000002', 'THOUGHT_RECORD', 'fr',
   'Une fiche de pensées aide à ralentir un moment difficile : ce qui s''est passé, la pensée qui est venue, l''émotion qu''elle a amenée, et un regard plus doux. Remarquer la pensée est déjà un pas.',
   'design:AssignHomework'),
  ('6f1c1a50-0001-4000-8000-000000000003', 'SLEEP_DIARY', 'en',
   'A sleep diary keeps track of bedtime, wake time and how rested you feel. Over a few nights it shows patterns you and your specialist can look at together.',
   'design:AssignHomework'),
  ('6f1c1a50-0001-4000-8000-000000000004', 'SLEEP_DIARY', 'fr',
   'Un journal du sommeil note l''heure du coucher, du réveil et la sensation de repos. Sur quelques nuits, il montre des habitudes à regarder avec votre spécialiste.',
   'design:AssignHomework'),
  ('6f1c1a50-0001-4000-8000-000000000005', 'WORRY_TIME', 'en',
   'Worry time is a set 15 minutes to note worries. When a worry comes at another time, you can tell yourself it has a place later, and come back to it then.',
   'design:AssignHomework'),
  ('6f1c1a50-0001-4000-8000-000000000006', 'WORRY_TIME', 'fr',
   'Le temps des soucis, ce sont 15 minutes fixes pour noter ses soucis. Quand un souci arrive à un autre moment, on peut se dire qu''il aura sa place plus tard, et y revenir à ce moment-là.',
   'design:AssignHomework');
