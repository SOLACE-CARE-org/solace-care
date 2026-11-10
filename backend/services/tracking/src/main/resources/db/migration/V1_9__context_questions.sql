-- Phase 2b, step 3 (D61; Tracking Service Spec, Context questions): the fixed tracking profile
-- becomes reviewed context questions with stored answers. A module lists the questions it asks on
-- enrolment, and a rule's "unless" reads a question by code. Answers can only switch rules off,
-- and a skipped question switches nothing.

CREATE TABLE context_question (
    id            UUID PRIMARY KEY,
    code          TEXT        NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    version       INTEGER     NOT NULL CHECK (version >= 1),
    status        TEXT        NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED')),
    type          TEXT        NOT NULL CHECK (type IN ('YES_NO', 'ONE_CHOICE', 'MANY_CHOICES')),
    scope         TEXT        NOT NULL CHECK (scope IN ('PROFILE', 'ENROLMENT')),
    -- {"en": {"question": "..."}, "fr": {"question": "..."}}
    labels_json   JSONB       NOT NULL,
    -- [{"code": "...", "labels": {"en": "...", "fr": "..."}}]; empty for YES_NO
    options_json  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    drafted_by    TEXT        NOT NULL,
    approved_by   TEXT,
    published_at  TIMESTAMPTZ,
    UNIQUE (code, version)
);

-- The two questions the launch rules read. The wording is provisional until a Clinical reviewer
-- signs it off.
INSERT INTO context_question (id, code, version, status, type, scope, labels_json, drafted_by, approved_by, published_at) VALUES
    ('0e1f6a10-0007-4000-8000-000000000001', 'trying_to_conceive', 1, 'PUBLISHED', 'YES_NO', 'PROFILE',
     '{"en": {"question": "Are you trying to conceive?"}, "fr": {"question": "Essayez-vous d''avoir un enfant ?"}}',
     'launch-seed', 'launch-seed', now()),
    ('0e1f6a10-0007-4000-8000-000000000002', 'hormonal_treatment', 1, 'PUBLISHED', 'YES_NO', 'PROFILE',
     '{"en": {"question": "Are you on hormonal treatment?"}, "fr": {"question": "Suivez-vous un traitement hormonal ?"}}',
     'launch-seed', 'launch-seed', now());

-- One answer per patient and question code: true or false, an option code, or a list of them.
-- Personal data: erased with everything else, never sent anywhere.
CREATE TABLE context_answer (
    user_id        UUID        NOT NULL,
    question_code  TEXT        NOT NULL,
    value_json     JSONB       NOT NULL,
    answered_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, question_code)
);

-- Carry the old profile over. PCOS status was never read by a rule and has no question, so it
-- is not kept.
INSERT INTO context_answer (user_id, question_code, value_json, answered_at)
SELECT user_id, 'trying_to_conceive', to_jsonb(trying_to_conceive), updated_at
  FROM tracking_profile WHERE trying_to_conceive IS NOT NULL;

INSERT INTO context_answer (user_id, question_code, value_json, answered_at)
SELECT user_id, 'hormonal_treatment', 'true'::jsonb, updated_at
  FROM tracking_profile WHERE 'hormonal' = ANY (treatments);

DROP TABLE tracking_profile;

-- The questions a module version asks on enrolment, in order (at most 3, checked by the service).
CREATE TABLE module_question (
    module_version_id  UUID    NOT NULL REFERENCES module_version (id),
    question_code      TEXT    NOT NULL,
    position           INTEGER NOT NULL,
    PRIMARY KEY (module_version_id, question_code)
);

INSERT INTO module_question (module_version_id, question_code, position)
SELECT v.id, q.code, q.position
  FROM module_version v
  JOIN module m ON m.id = v.module_id AND m.code = 'pcos'
 CROSS JOIN (VALUES ('trying_to_conceive', 0), ('hormonal_treatment', 1)) AS q (code, position)
 WHERE v.status = 'DRAFT';

-- Draft rules read the questions by code. Published rules keep the old "field" form, which the
-- service still reads the same way.
UPDATE rule r SET unless_json = (
    SELECT coalesce(jsonb_agg(
               CASE
                   WHEN e ->> 'field' = 'treatments' AND e ->> 'includes' = 'hormonal'
                       THEN '{"question": "hormonal_treatment", "equals": true}'::jsonb
                   WHEN e ->> 'field' = 'trying_to_conceive'
                       THEN jsonb_build_object('question', 'trying_to_conceive', 'equals', e -> 'equals')
                   ELSE e
               END ORDER BY i), '[]'::jsonb)
      FROM jsonb_array_elements(r.unless_json) WITH ORDINALITY AS u (e, i))
  FROM module_version v
 WHERE v.id = r.module_version_id
   AND v.status = 'DRAFT'
   AND jsonb_path_exists(r.unless_json, '$[*] ? (exists (@.field))');
