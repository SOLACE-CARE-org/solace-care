-- Specialties and search (Phase 1, epic 3; D31).

-- System Design, Core data model: specialty. Data in EN and FR, managed by admins later.
CREATE TABLE specialty (
    code         TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]*$'),
    label_en     TEXT    NOT NULL,
    label_fr     TEXT    NOT NULL,
    parent_code  TEXT REFERENCES specialty (code),
    position     INTEGER NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE
);

-- The physical-health specialties wait, disabled, with their professions (D26).
INSERT INTO specialty (code, label_en, label_fr, position, enabled) VALUES
    ('clinical-psychology', 'Clinical psychology', 'Psychologie clinique',   1, TRUE),
    ('counselling',         'Counselling',         'Conseil psychologique',  2, TRUE),
    ('psychiatry',          'Psychiatry',          'Psychiatrie',            3, TRUE),
    ('gynaecology',         'Gynaecology',         'Gynécologie',            4, FALSE),
    ('endocrinology',       'Endocrinology',       'Endocrinologie',         5, FALSE);

-- Each profession belongs to one specialty for now (D31).
ALTER TABLE profession ADD COLUMN specialty_code TEXT REFERENCES specialty (code);
UPDATE profession SET specialty_code = CASE code
    WHEN 'clinical-psychologist' THEN 'clinical-psychology'
    WHEN 'licensed-counsellor'   THEN 'counselling'
    WHEN 'psychiatrist'          THEN 'psychiatry'
    WHEN 'gynaecologist'         THEN 'gynaecology'
    WHEN 'endocrinologist'       THEN 'endocrinology'
END;
ALTER TABLE profession ALTER COLUMN specialty_code SET NOT NULL;

-- System Design, Core data model: service_offering.specialty_id. Kept in step with the
-- specialist's profession.
ALTER TABLE service_offering ADD COLUMN specialty_code TEXT REFERENCES specialty (code);
UPDATE service_offering o
   SET specialty_code = pr.specialty_code
  FROM specialist_profile p JOIN profession pr ON pr.code = p.profession_code
 WHERE p.account_id = o.specialist_id;

-- Search matches "anxiete" to "anxiété". A trusted extension: the database owner may create it.
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE INDEX specialist_language_bookable_idx
    ON specialist_language (language_code, account_id)
    WHERE verified_level IN ('NATIVE', 'FLUENT', 'PROFESSIONAL');
