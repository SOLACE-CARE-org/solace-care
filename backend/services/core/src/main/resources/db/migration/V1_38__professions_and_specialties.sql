-- Several professions per specialist, several specialties per profession, and requests for
-- specialties not listed yet (D62). Replaces "each profession belongs to one specialty" (D31).

-- --- The catalogue ------------------------------------------------------------------------------

-- Which specialties each profession allows, one row per pair. Staff manage it on the Catalogue
-- screen; a created or matched specialty request adds a row. A changed link applies to new choices
-- only, so specialist_specialty does not reference it.
CREATE TABLE profession_specialty (
    profession_code  TEXT NOT NULL REFERENCES profession (code),
    specialty_code   TEXT NOT NULL REFERENCES specialty (code),
    PRIMARY KEY (profession_code, specialty_code)
);

INSERT INTO profession_specialty (profession_code, specialty_code)
SELECT code, specialty_code FROM profession;

ALTER TABLE profession DROP COLUMN specialty_code;

CREATE INDEX specialty_parent_idx ON specialty (parent_code);

-- --- A specialist's practice --------------------------------------------------------------------

-- Each profession a specialist claims, with its own licence. Position 1 is the main profession.
CREATE TABLE specialist_profession (
    account_id           UUID        NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    profession_code      TEXT        NOT NULL REFERENCES profession (code),
    position             INTEGER     NOT NULL CHECK (position >= 1),
    licence_number       TEXT        NOT NULL CHECK (length(licence_number) BETWEEN 1 AND 60),
    licensing_body       TEXT        NOT NULL CHECK (length(licensing_body) BETWEEN 1 AND 160),
    status               TEXT        NOT NULL
                         CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- Added from the Profile tab after approval: two people approve it (D29).
    added_after_approval BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Recorded by the reviewer who checked the licence; the record reminds 60 days before.
    licence_valid_until  DATE,
    verified_by_id       TEXT,
    verified_by_name     TEXT,
    verified_at          TIMESTAMPTZ,
    decided_by_id        TEXT,
    decided_by_name      TEXT,
    decided_at           TIMESTAMPTZ,
    -- The reviewer's reason for a rejection, shown to the specialist.
    review_note          TEXT        CHECK (length(review_note) <= 1000),
    requested_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, profession_code)
);

CREATE INDEX specialist_profession_pending_idx ON specialist_profession (requested_at)
    WHERE status = 'PENDING' AND added_after_approval;

-- The specialties a specialist offers under one of their professions.
CREATE TABLE specialist_specialty (
    account_id       UUID        NOT NULL,
    profession_code  TEXT        NOT NULL,
    specialty_code   TEXT        NOT NULL REFERENCES specialty (code),
    status           TEXT        NOT NULL
                     CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    -- The specialist's training or experience in it, required when added after approval.
    note             TEXT        CHECK (length(note) BETWEEN 1 AND 1000),
    certificate_id   UUID,
    verified_by_id   TEXT,
    verified_by_name TEXT,
    verified_at      TIMESTAMPTZ,
    decided_by_id    TEXT,
    decided_by_name  TEXT,
    decided_at       TIMESTAMPTZ,
    review_note      TEXT        CHECK (length(review_note) <= 1000),
    requested_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, profession_code, specialty_code),
    FOREIGN KEY (account_id, profession_code)
        REFERENCES specialist_profession (account_id, profession_code) ON DELETE CASCADE
);

CREATE INDEX specialist_specialty_search_idx ON specialist_specialty (specialty_code, account_id)
    WHERE status = 'APPROVED';
CREATE INDEX specialist_specialty_pending_idx ON specialist_specialty (requested_at)
    WHERE status = 'PENDING';

-- Requests for a specialty the catalogue does not have yet.
CREATE TABLE specialty_request (
    id               UUID PRIMARY KEY,
    account_id       UUID        NOT NULL,
    profession_code  TEXT        NOT NULL,
    proposed_name    TEXT        NOT NULL CHECK (length(proposed_name) BETWEEN 1 AND 80),
    note             TEXT        NOT NULL CHECK (length(note) BETWEEN 1 AND 1000),
    certificate_id   UUID,
    status           TEXT        NOT NULL
                     CHECK (status IN ('OPEN', 'CREATED', 'MATCHED', 'DECLINED', 'WITHDRAWN')),
    -- What it became or was matched to.
    specialty_code   TEXT        REFERENCES specialty (code),
    decided_by_id    TEXT,
    decided_by_name  TEXT,
    decided_at       TIMESTAMPTZ,
    review_note      TEXT        CHECK (length(review_note) <= 1000),
    requested_at     TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (account_id, profession_code)
        REFERENCES specialist_profession (account_id, profession_code) ON DELETE CASCADE
);

CREATE INDEX specialty_request_open_idx ON specialty_request (requested_at) WHERE status = 'OPEN';
CREATE INDEX specialty_request_account_idx ON specialty_request (account_id, requested_at DESC);

-- --- Documents ----------------------------------------------------------------------------------

-- A LICENCE is for one profession; a CERTIFICATE comes with a specialty or a request.
ALTER TABLE specialist_document ADD COLUMN profession_code TEXT;
ALTER TABLE specialist_document DROP CONSTRAINT specialist_document_kind_check;
ALTER TABLE specialist_document ADD CONSTRAINT specialist_document_kind_check
    CHECK (kind IN ('LICENCE', 'IDENTITY', 'PHOTO', 'CV', 'CERTIFICATE'));

ALTER TABLE specialist_specialty ADD CONSTRAINT specialist_specialty_certificate_fk
    FOREIGN KEY (certificate_id) REFERENCES specialist_document (id) ON DELETE SET NULL;
ALTER TABLE specialty_request ADD CONSTRAINT specialty_request_certificate_fk
    FOREIGN KEY (certificate_id) REFERENCES specialist_document (id) ON DELETE SET NULL;

-- --- Today's specialists, moved over ------------------------------------------------------------

-- Each profile's profession becomes its main profession, approved if the specialist is.
INSERT INTO specialist_profession
       (account_id, profession_code, position, licence_number, licensing_body, status,
        licence_valid_until, verified_by_id, verified_by_name, verified_at, decided_at, requested_at)
SELECT p.account_id, p.profession_code, 1, p.licence_number, p.licensing_body,
       CASE WHEN p.verification_status IN ('APPROVED', 'SUSPENDED') THEN 'APPROVED' ELSE 'PENDING' END,
       p.licence_valid_until, i.verified_by_id, i.verified_by_name, i.verified_at,
       CASE WHEN p.verification_status IN ('APPROVED', 'SUSPENDED') THEN p.decided_at END,
       COALESCE(p.practice_saved_at, p.created_at)
  FROM specialist_profile p
  LEFT JOIN specialist_review_item i ON i.account_id = p.account_id AND i.item = 'LICENCE'
 WHERE p.profession_code IS NOT NULL;

-- With the specialty their profession had.
INSERT INTO specialist_specialty
       (account_id, profession_code, specialty_code, status, verified_by_id, verified_by_name,
        verified_at, decided_at, requested_at)
SELECT sp.account_id, sp.profession_code, ps.specialty_code, sp.status, sp.verified_by_id,
       sp.verified_by_name, sp.verified_at, sp.decided_at, sp.requested_at
  FROM specialist_profession sp
  JOIN profession_specialty ps ON ps.profession_code = sp.profession_code;

UPDATE specialist_document d
   SET profession_code = p.profession_code
  FROM specialist_profile p
 WHERE p.account_id = d.account_id AND d.kind = 'LICENCE';

-- The licence is now checked per profession.
DELETE FROM specialist_review_item WHERE item = 'LICENCE';
ALTER TABLE specialist_review_item DROP CONSTRAINT specialist_review_item_item_check;
ALTER TABLE specialist_review_item ADD CONSTRAINT specialist_review_item_item_check
    CHECK (item IN ('IDENTITY', 'PROFILE', 'PAYOUT'));

ALTER TABLE specialist_profile
    DROP COLUMN profession_code,
    DROP COLUMN licence_number,
    DROP COLUMN licensing_body,
    DROP COLUMN licence_valid_until;

-- Sessions are booked with the specialist, not a profession: the specialty lives on
-- specialist_specialty now.
ALTER TABLE service_offering DROP COLUMN specialty_code;

-- --- The activity log ---------------------------------------------------------------------------

ALTER TABLE specialist_review_activity DROP CONSTRAINT specialist_review_activity_kind_check;
ALTER TABLE specialist_review_activity ADD CONSTRAINT specialist_review_activity_kind_check
    CHECK (kind IN ('SUBMITTED', 'OPENED', 'ITEM_VERIFIED', 'ITEM_UNVERIFIED', 'LANGUAGE_VERIFIED',
                    'BIO_ADDED', 'INTERVIEW_BOOKED', 'CHANGES_REQUESTED', 'APPROVAL_PROPOSED',
                    'APPROVAL_WITHDRAWN', 'APPROVED', 'REJECTED', 'SUSPENDED', 'REINSTATED',
                    'PROFESSION_ADDED', 'PROFESSION_VERIFIED', 'PROFESSION_UNVERIFIED',
                    'PROFESSION_APPROVED', 'PROFESSION_REJECTED', 'PROFESSION_WITHDRAWN',
                    'SPECIALTY_ADDED', 'SPECIALTY_VERIFIED', 'SPECIALTY_UNVERIFIED',
                    'SPECIALTY_APPROVED', 'SPECIALTY_DECLINED', 'SPECIALTY_WITHDRAWN',
                    'SPECIALTY_REQUESTED', 'REQUEST_CREATED', 'REQUEST_MATCHED',
                    'REQUEST_DECLINED', 'REQUEST_WITHDRAWN'));
